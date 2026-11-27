package com.react.ai.service;

import com.react.ai.config.AudienceAnalysisCostProperties;
import com.react.ai.config.AudienceAnalysisRetryProperties;
import com.react.ai.domain.AiAnalysisBatchStatus;
import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.domain.AiLlmCallStatus;
import com.react.ai.dto.AiLlmCallUsage;
import com.react.ai.dto.AudienceAnalysisBatch;
import com.react.ai.dto.AudienceAnalysisLlmResponse;
import com.react.ai.exception.GeminiAudienceAnalysisErrorCode;
import com.react.ai.exception.GeminiAudienceAnalysisException;
import com.react.ai.exception.InvalidAiAnalysisResultException;
import com.react.ai.exception.InvalidStoredAiBatchDataException;
import com.react.ai.repository.AiAnalysisBatchAttemptRepository;
import com.react.ai.repository.AiAnalysisBatchRepository;
import com.react.ai.repository.AiAnalysisRepository;
import com.react.ai.repository.AiLlmCallUsageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** 입력 상한을 넘는 분석의 배치를 실행한다. 배치별 DB 작업만 짧게 실행한다. */
@Service
@RequiredArgsConstructor
public class AudienceAnalysisBatchService {
    private static final Logger log = LoggerFactory.getLogger(AudienceAnalysisBatchService.class);
    private final AiAnalysisRepository analyses;
    private final AiAnalysisBatchRepository batches;
    private final AiAnalysisBatchAttemptRepository attempts;
    private final AudienceAnalysisBatchInputRestorer inputRestorer;
    private final AudienceAnalysisLlmClient llm;
    private final AiAnalysisJsonParser parser;
    private final AiLlmCallUsageRepository usages;
    private final AudienceAnalysisRetryProperties retry;
    private final AudienceAnalysisCostProperties costs;
    private final AiAnalysisSourceValidator sources = new AiAnalysisSourceValidator();
    private final AiLlmCostCalculator costCalculator = new AiLlmCostCalculator();

    public RunResult process(long analysisId, Duration leaseDuration) {
        return process(analysisId, leaseDuration, () -> true);
    }

    public RunResult process(long analysisId, Duration leaseDuration, BooleanSupplier mayContinue) {
        var analysis = analyses.findById(analysisId)
                .orElseThrow(() -> new IllegalArgumentException("AI 분석을 찾을 수 없습니다."));
        if (analysis.status() != AiAnalysisStatus.PROCESSING) {
            return new RunResult(0, BatchOutcome.NOT_PROCESSING);
        }
        var storedBatches = batches.findByAnalysis(analysisId);
        if (storedBatches.isEmpty()) {
            throw new IllegalStateException("분석에 저장된 AI 배치가 없습니다.");
        }
        int completed = 0;
        for (var stored : storedBatches) {
            if (stored.status() == AiAnalysisBatchStatus.COMPLETED) {
                continue;
            }
            if (stored.status() == AiAnalysisBatchStatus.FAILED) {
                return new RunResult(completed, BatchOutcome.FAILED);
            }
            if (!mayContinue.getAsBoolean()) {
                return new RunResult(completed, BatchOutcome.IN_PROGRESS);
            }
            UUID token = UUID.randomUUID();
            if (!batches.claim(stored.id(), token, leaseDuration)) {
                return new RunResult(completed, BatchOutcome.IN_PROGRESS);
            }
            try {
                AudienceAnalysisBatch input = inputRestorer.restore(analysis, stored);
                AudienceAnalysisLlmResponse response = analyzeWithRetry(analysisId, stored.id(), token, input);
                if (response == null || response.content() == null || response.modelName() == null
                        || response.modelName().isBlank() || response.modelName().length() > 100) {
                    throw new InvalidAiAnalysisResultException("LLM 응답에 모델 정보 또는 결과가 없습니다.");
                }
                var result = parser.parse(response.content());
                Set<Long> representativeIds = new HashSet<>();
                input.inputMessages().forEach(message -> representativeIds.add(message.messageId()));
                sources.validate(representativeIds, result);
                var expanded = new com.react.ai.dto.PreprocessedAudienceMessages(input.inputMessages(),
                        input.originalMessageIdsByRepresentative()).expandSourceMessageIds(result);
                Set<Long> originalIds = new HashSet<>();
                input.originalMessageIdsByRepresentative().values().forEach(originalIds::addAll);
                sources.validate(originalIds, expanded);
                if (!batches.saveValidatedResult(stored.id(), token, result, response.modelName())) {
                    return new RunResult(completed, BatchOutcome.CLAIM_LOST);
                }
                completed++;
                if (!mayContinue.getAsBoolean()) {
                    return new RunResult(completed, BatchOutcome.IN_PROGRESS);
                }
            } catch (ClaimLostException e) {
                return new RunResult(completed, BatchOutcome.CLAIM_LOST);
            } catch (RuntimeException e) {
                if (isTransientInfrastructureFailure(e)) {
                    // 현재 claim은 lease 만료 뒤 다시 획득된다. 부모 상태를 FAILED로 바꾸지 않는다.
                    return new RunResult(completed, BatchOutcome.IN_PROGRESS);
                }
                if (!batches.markFailed(stored.id(), token, safeErrorCode(e))) {
                    return new RunResult(completed, BatchOutcome.CLAIM_LOST);
                }
                return new RunResult(completed, BatchOutcome.FAILED);
            }
        }
        return new RunResult(completed, BatchOutcome.ALL_BATCHES_COMPLETED);
    }

    private AudienceAnalysisLlmResponse analyzeWithRetry(long analysisId, long batchId, UUID token,
                                                         AudienceAnalysisBatch input) {
        int retryCount = 0;
        while (true) {
            if (!batches.isClaimCurrent(batchId, token)) {
                throw new ClaimLostException();
            }
            int attemptNumber = attempts.reserve(analysisId);
            if (!batches.isClaimCurrent(batchId, token)) {
                throw new ClaimLostException();
            }
            long started = System.nanoTime();
            try {
                var response = llm.analyze(input.inputMessages());
                recordUsage(analysisId, attemptNumber, response == null ? null : response.modelName(),
                        AiLlmCallStatus.SUCCESS, null, response == null ? null : response.usage(), elapsed(started));
                return response;
            } catch (GeminiAudienceAnalysisException e) {
                recordUsage(analysisId, attemptNumber, llm.modelName(), AiLlmCallStatus.FAILED,
                        e.errorCode().name(), e.usage(), elapsed(started));
                if (!e.retryable() || retryCount >= retry.maxRetries()) {
                    throw e;
                }
                retryCount++;
                waitBeforeRetry(e);
            } catch (RuntimeException e) {
                recordUsage(analysisId, attemptNumber, llm.modelName(), AiLlmCallStatus.FAILED,
                        "AI_ANALYSIS_PROCESSING_FAILED", null, elapsed(started));
                throw e;
            }
        }
    }

    private void recordUsage(long analysisId, int number, String model, AiLlmCallStatus status,
                             String error, AudienceAnalysisLlmResponse.Usage usage, long durationMs) {
        try {
            var estimate = costCalculator.estimate(usage, costs).orElse(null);
            usages.save(new AiLlmCallUsage(analysisId, number,
                    model == null || model.isBlank() || model.length() > 100 ? null : model,
                    status, error, usage == null ? null : usage.promptTokenCount(),
                    usage == null ? null : usage.outputTokenCount(),
                    usage == null ? null : usage.totalTokenCount(), durationMs,
                    estimate == null ? null : estimate.inputUsdPerMillionTokens(),
                    estimate == null ? null : estimate.outputUsdPerMillionTokens(),
                    estimate == null ? null : estimate.estimatedCostMicrousd()));
        } catch (RuntimeException e) {
            log.warn("AI 배치 Gemini 호출 이력 저장에 실패했습니다. analysisId={}, attemptNumber={}",
                    analysisId, number);
        }
    }

    private long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private void waitBeforeRetry(GeminiAudienceAnalysisException e) {
        Duration delay = e.retryAfter();
        if (delay == null || delay.isNegative()) {
            delay = retry.retryDelay();
        } else if (delay.compareTo(retry.maxRetryAfter()) > 0) {
            delay = retry.maxRetryAfter();
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GeminiAudienceAnalysisException(GeminiAudienceAnalysisErrorCode.INTERRUPTED,
                    false, null, "Gemini 분석 재시도 대기 중 인터럽트되었습니다.");
        }
    }

    private String safeErrorCode(RuntimeException e) {
        if (e instanceof GeminiAudienceAnalysisException gemini) {
            return gemini.errorCode().name();
        }
        if (e instanceof InvalidAiAnalysisResultException) {
            return "INVALID_LLM_RESPONSE";
        }
        if (e instanceof InvalidStoredAiBatchDataException storedData) {
            return storedData.errorCode();
        }
        return "AI_ANALYSIS_PROCESSING_FAILED";
    }

    private boolean isTransientInfrastructureFailure(RuntimeException e) {
        return e instanceof TransientDataAccessException
                || e instanceof RecoverableDataAccessException
                || e instanceof DataAccessResourceFailureException;
    }

    public enum BatchOutcome { ALL_BATCHES_COMPLETED, IN_PROGRESS, FAILED, CLAIM_LOST, NOT_PROCESSING }
    public record RunResult(int completedInThisRun, BatchOutcome outcome) { }
    private static final class ClaimLostException extends RuntimeException { }
}
