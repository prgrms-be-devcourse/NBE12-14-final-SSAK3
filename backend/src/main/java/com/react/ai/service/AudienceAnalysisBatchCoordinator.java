package com.react.ai.service;

import com.react.ai.config.AiAnalysisSchedulerProperties;
import com.react.ai.config.GeminiAudienceAnalysisProperties;
import com.react.ai.domain.AiAnalysisBatchStatus;
import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.dto.AiAnalysisProcessResult;
import com.react.ai.dto.AiAnalysisResult;
import com.react.ai.dto.AiTopicResult;
import com.react.ai.dto.PreprocessedAudienceMessages;
import com.react.ai.exception.GeminiAudienceAnalysisException;
import com.react.ai.exception.InvalidAiAnalysisResultException;
import com.react.ai.repository.AiAnalysisBatchRepository;
import com.react.ai.repository.AiAnalysisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** 배치 중간 결과는 내부에 보관하고, 모든 배치가 완료된 뒤에만 기존 분석 결과를 확정한다. */
@Component
@RequiredArgsConstructor
public class AudienceAnalysisBatchCoordinator {
    private final AiAnalysisRepository analyses;
    private final AiAnalysisBatchRepository batches;
    private final AudienceAnalysisBatchSplitter splitter;
    private final AudienceAnalysisBatchService batchService;
    private final AudienceAnalysisBatchInputRestorer restorer;
    private final AiAnalysisJsonParser parser;
    private final AiAnalysisCompletionService completions;
    private final GeminiAudienceAnalysisProperties gemini;
    private final AiAnalysisSchedulerProperties scheduler;
    private final AiAnalysisSourceValidator sources = new AiAnalysisSourceValidator();

    public boolean needsBatch(PreprocessedAudienceMessages input) {
        return input.inputMessages().size() > gemini.maxMessages()
                || input.inputMessages().stream().mapToLong(message -> message.content().length()).sum()
                > gemini.maxInputCharacters();
    }

    public boolean hasBatches(long analysisId) {
        return batches.existsForAnalysis(analysisId);
    }

    public AiAnalysisProcessResult start(long roomId, long analysisId, PreprocessedAudienceMessages input,
                                         BooleanSupplier mayContinue) {
        return start(roomId, analysisId, input, true, mayContinue);
    }

    /** manifest 없이 남은 PROCESSING 부모를 재시작 후 같은 입력 규칙으로 복구한다. */
    public AiAnalysisProcessResult recoverMissingManifest(long roomId, long analysisId,
                                                           PreprocessedAudienceMessages input,
                                                           BooleanSupplier mayContinue) {
        return start(roomId, analysisId, input, false, mayContinue);
    }

    private AiAnalysisProcessResult start(long roomId, long analysisId, PreprocessedAudienceMessages input,
                                          boolean created, BooleanSupplier mayContinue) {
        try {
            var initialized = batches.initializeForProcessing(analysisId,
                    splitter.split(input, gemini.maxMessages(), gemini.maxInputCharacters()));
            if (initialized.status() != AiAnalysisStatus.PROCESSING) {
                return new AiAnalysisProcessResult(analysisId, initialized.status(), false, 0);
            }
        } catch (DuplicateKeyException ignored) {
            // 이전 배포 중 생성된 manifest와의 경쟁은 아래 resume의 claim으로 조정한다.
        } catch (GeminiAudienceAnalysisException e) {
            return fail(analysisId, created, e.errorCode().name());
        } catch (RuntimeException e) {
            // manifest 저장·조회 인프라 오류는 부모를 종결하지 않는다. 다음 스케줄러가 다시 시도한다.
            return current(analysisId);
        }
        return resume(roomId, analysisId, created, mayContinue);
    }

    public AiAnalysisProcessResult resume(long roomId, long analysisId, boolean created,
                                          BooleanSupplier mayContinue) {
        AudienceAnalysisBatchService.RunResult run;
        try {
            run = batchService.process(analysisId, scheduler.finalizationLeaseDuration(), mayContinue);
        } catch (RuntimeException e) {
            // 다른 작업자의 DB 조회·연결 오류는 현재 claim을 보유한 작업자의 부모 상태를 바꾸면 안 된다.
            return current(analysisId);
        }
        if (run.outcome() == AudienceAnalysisBatchService.BatchOutcome.NOT_PROCESSING) {
            return current(analysisId);
        }
        if (run.outcome() == AudienceAnalysisBatchService.BatchOutcome.FAILED) {
            try {
                var failed = batches.findByAnalysis(analysisId).stream()
                        .filter(batch -> batch.status() == AiAnalysisBatchStatus.FAILED).findFirst().orElseThrow();
                return fail(analysisId, created, failed.errorCode());
            } catch (RuntimeException e) {
                return current(analysisId);
            }
        }
        if (run.outcome() != AudienceAnalysisBatchService.BatchOutcome.ALL_BATCHES_COMPLETED) {
            return processing(analysisId, created);
        }
        try {
            return complete(roomId, analysisId, created);
        } catch (RuntimeException e) {
            if (isTerminalInputFailure(e)) {
                String errorCode = e instanceof InvalidAiAnalysisResultException
                        ? "INVALID_LLM_RESPONSE" : "INPUT_MANIFEST_MISMATCH";
                return fail(analysisId, created, errorCode);
            }
            // 최종 저장 전의 DB 오류는 모든 배치 결과를 보존한 채 다음 실행에서 재확정한다.
            return current(analysisId);
        }
    }

    private boolean isTerminalInputFailure(RuntimeException e) {
        return e instanceof InvalidAiAnalysisResultException
                || e instanceof IllegalStateException
                && "AI_BATCH_INPUT_MANIFEST_MISMATCH".equals(e.getMessage());
    }

    private AiAnalysisProcessResult fail(long analysisId, boolean created, String errorCode) {
        try {
            if (!analyses.markFailed(analysisId, errorCode)) {
                return current(analysisId);
            }
            return new AiAnalysisProcessResult(analysisId, AiAnalysisStatus.FAILED, created, 0);
        } catch (RuntimeException e) {
            return current(analysisId);
        }
    }

    private AiAnalysisProcessResult processing(long analysisId, boolean created) {
        return new AiAnalysisProcessResult(analysisId, AiAnalysisStatus.PROCESSING, created, 0);
    }

    private AiAnalysisProcessResult complete(long roomId, long analysisId, boolean created) {
        var analysis = analyses.findById(analysisId).orElseThrow();
        if (analysis.roomId() != roomId || analysis.status() != AiAnalysisStatus.PROCESSING) {
            return current(analysisId);
        }
        var stored = batches.findByAnalysis(analysisId);
        if (stored.isEmpty() || stored.stream().anyMatch(batch -> batch.status() != AiAnalysisBatchStatus.COMPLETED)) {
            return new AiAnalysisProcessResult(analysisId, AiAnalysisStatus.PROCESSING, created, 0);
        }
        List<AiTopicResult> topics = new ArrayList<>();
        String modelName = null;
        for (int index = 0; index < stored.size(); index++) {
            var batch = stored.get(index);
            if (batch.batchIndex() != index || batch.modelName() == null || batch.modelName().isBlank()) {
                throw new InvalidAiAnalysisResultException("저장된 AI 배치 정보가 올바르지 않습니다.");
            }
            if (modelName == null) {
                modelName = batch.modelName();
            } else if (!modelName.equals(batch.modelName())) {
                throw new InvalidAiAnalysisResultException("배치의 Gemini 모델이 서로 다릅니다.");
            }
            var input = restorer.restore(analysis, batch);
            AiAnalysisResult result = parser.parse(batches.findResultJson(batch.id()));
            Set<Long> representatives = new HashSet<>();
            input.inputMessages().forEach(message -> representatives.add(message.messageId()));
            sources.validate(representatives, result);
            var expanded = new PreprocessedAudienceMessages(input.inputMessages(),
                    input.originalMessageIdsByRepresentative()).expandSourceMessageIds(result);
            topics.addAll(expanded.topics());
        }
        AiAnalysisResult combined = new AiAnalysisResult(List.copyOf(topics));
        Set<Long> allowedOriginals = new HashSet<>();
        for (var batch : stored) {
            batch.inputManifest().messages().forEach(message -> allowedOriginals.addAll(message.originalMessageIds()));
        }
        sources.validate(allowedOriginals, combined);
        completions.completeWithTopics(roomId, analysisId, combined, modelName);
        return new AiAnalysisProcessResult(analysisId, AiAnalysisStatus.COMPLETED, created, topics.size());
    }

    private AiAnalysisProcessResult current(long analysisId) {
        var analysis = analyses.findById(analysisId).orElseThrow();
        return new AiAnalysisProcessResult(analysisId, analysis.status(), false, 0);
    }
}
