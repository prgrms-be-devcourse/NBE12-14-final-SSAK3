package com.react.ai.service;

import com.react.ai.config.AudienceAnalysisRetryProperties;
import com.react.ai.config.AudienceAnalysisCostProperties;
import com.react.ai.domain.AiLlmCallStatus;
import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.dto.AiAnalysisProcessResult;
import com.react.ai.dto.AiAnalysisResult;
import com.react.ai.dto.AiTopicResult;
import com.react.ai.dto.AudienceAnalysisLlmResponse;
import com.react.ai.dto.AudienceMessage;
import com.react.ai.dto.AiLlmCallUsage;
import com.react.ai.exception.InvalidAiAnalysisResultException;
import com.react.ai.exception.GeminiAudienceAnalysisErrorCode;
import com.react.ai.exception.GeminiAudienceAnalysisException;
import com.react.ai.repository.AiAnalysisRepository;
import com.react.ai.repository.AiLlmCallUsageRepository;
import com.react.ai.repository.AudienceMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 스케줄러와 외부 LLM 제공자에 의존하지 않는 분석 처리 코어다.
 */
public class AudienceAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(AudienceAnalysisService.class);
    private final AiAnalysisRepository analyses;
    private final AudienceMessageRepository messages;
    private final AudienceAnalysisLlmClient llmClient;
    private final AiAnalysisJsonParser jsonParser;
    private final AudienceAnalysisRetryProperties retryProperties;
    private final AiLlmCallUsageRepository usages;
    private final AudienceAnalysisCostProperties costProperties;
    private final AiLlmCostCalculator costCalculator;
    private final AiAnalysisCompletionService completions;

    public AudienceAnalysisService(AiAnalysisRepository analyses, AudienceMessageRepository messages,
                                   AudienceAnalysisLlmClient llmClient, AiAnalysisJsonParser jsonParser) {
        this(analyses, messages, llmClient, jsonParser,
                new AudienceAnalysisRetryProperties(1, Duration.ZERO, Duration.ofSeconds(5)), null,
                new AudienceAnalysisCostProperties(null, null), noOpCompletions(analyses));
    }

    public AudienceAnalysisService(AiAnalysisRepository analyses, AudienceMessageRepository messages,
                                   AudienceAnalysisLlmClient llmClient, AiAnalysisJsonParser jsonParser,
                                   AudienceAnalysisRetryProperties retryProperties) {
        this(analyses, messages, llmClient, jsonParser, retryProperties, null,
                new AudienceAnalysisCostProperties(null, null), noOpCompletions(analyses));
    }

    public AudienceAnalysisService(AiAnalysisRepository analyses, AudienceMessageRepository messages,
                                   AudienceAnalysisLlmClient llmClient, AiAnalysisJsonParser jsonParser,
                                   AudienceAnalysisRetryProperties retryProperties,
                                   AiLlmCallUsageRepository usages,
                                   AudienceAnalysisCostProperties costProperties) {
        this(analyses, messages, llmClient, jsonParser, retryProperties, usages, costProperties,
                noOpCompletions(analyses));
    }

    public AudienceAnalysisService(AiAnalysisRepository analyses, AudienceMessageRepository messages,
                                   AudienceAnalysisLlmClient llmClient, AiAnalysisJsonParser jsonParser,
                                   AudienceAnalysisRetryProperties retryProperties,
                                   AiLlmCallUsageRepository usages,
                                   AudienceAnalysisCostProperties costProperties,
                                   AiAnalysisCompletionService completions) {
        this.analyses = analyses;
        this.messages = messages;
        this.llmClient = llmClient;
        this.jsonParser = jsonParser;
        this.retryProperties = retryProperties;
        this.usages = usages;
        this.costProperties = costProperties;
        this.costCalculator = new AiLlmCostCalculator();
        this.completions = completions;
    }

    public AiAnalysisProcessResult process(long roomId, LocalDateTime windowStartAt, LocalDateTime windowEndAt) {
        validateWindow(roomId, windowStartAt, windowEndAt);
        var acquired = analyses.acquire(roomId, windowStartAt, windowEndAt);
        if (!acquired.created()) {
            return new AiAnalysisProcessResult(acquired.analysis().id(), acquired.analysis().status(), false, 0);
        }
        if (!analyses.markProcessing(acquired.analysis().id())) {
            throw new IllegalStateException("분석 처리 상태로 변경할 수 없습니다.");
        }

        try {
            List<AudienceMessage> candidates = messages.findAudienceMessages(roomId, windowStartAt, windowEndAt);
            if (candidates.isEmpty()) {
                if (!completions.completeEmpty(roomId, acquired.analysis().id())) {
                    throw new IllegalStateException("빈 분석 구간을 완료 상태로 변경할 수 없습니다.");
                }
                return new AiAnalysisProcessResult(acquired.analysis().id(), AiAnalysisStatus.COMPLETED, true, 0);
            }

            AudienceAnalysisLlmResponse response = analyzeWithRetry(acquired.analysis().id(), candidates);
            if (response == null || response.content() == null || response.modelName() == null
                    || response.modelName().isBlank() || response.modelName().length() > 100) {
                throw new InvalidAiAnalysisResultException("LLM 응답에 모델 정보 또는 결과가 없습니다.");
            }
            AiAnalysisResult result = jsonParser.parse(response.content());
            validateSourceMessages(candidates, result);
            completions.completeWithTopics(roomId, acquired.analysis().id(), result, response.modelName());
            return new AiAnalysisProcessResult(acquired.analysis().id(), AiAnalysisStatus.COMPLETED, true,
                    result.topics().size());
        } catch (Exception e) {
            if (!analyses.markFailed(acquired.analysis().id(), safeErrorMessage(e))) {
                throw new IllegalStateException("분석 실패 상태로 변경할 수 없습니다.", e);
            }
            return new AiAnalysisProcessResult(acquired.analysis().id(), AiAnalysisStatus.FAILED, true, 0);
        }
    }

    private void validateWindow(long roomId, LocalDateTime windowStartAt, LocalDateTime windowEndAt) {
        if (roomId <= 0 || windowStartAt == null || windowEndAt == null || !windowStartAt.isBefore(windowEndAt)) {
            throw new IllegalArgumentException("분석 구간이 올바르지 않습니다.");
        }
    }

    private AudienceAnalysisLlmResponse analyzeWithRetry(long analysisId, List<AudienceMessage> candidates) {
        int retryCount = 0;
        while (true) {
            int attemptNumber = retryCount + 1;
            long startedAtNanos = System.nanoTime();
            try {
                AudienceAnalysisLlmResponse response = llmClient.analyze(candidates);
                recordSuccessfulCall(analysisId, attemptNumber, response, elapsedMillis(startedAtNanos));
                return response;
            } catch (GeminiAudienceAnalysisException e) {
                recordFailedCall(analysisId, attemptNumber, e.errorCode().name(), e.usage(),
                        elapsedMillis(startedAtNanos));
                if (!e.retryable() || retryCount >= retryProperties.maxRetries()) {
                    throw e;
                }
                retryCount++;
                waitBeforeRetry(e);
            } catch (RuntimeException e) {
                recordFailedCall(analysisId, attemptNumber, "AI_ANALYSIS_PROCESSING_FAILED", null,
                        elapsedMillis(startedAtNanos));
                throw e;
            }
        }
    }

    private long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private void recordSuccessfulCall(long analysisId, int attemptNumber, AudienceAnalysisLlmResponse response,
                                      long durationMs) {
        try {
            AudienceAnalysisLlmResponse.Usage usage = response.usage();
            var estimate = costCalculator.estimate(usage, costProperties).orElse(null);
            saveUsage(new AiLlmCallUsage(
                    analysisId, attemptNumber, safeModelName(response.modelName()), AiLlmCallStatus.SUCCESS, null,
                    usage == null ? null : usage.promptTokenCount(),
                    usage == null ? null : usage.outputTokenCount(),
                    usage == null ? null : usage.totalTokenCount(),
                    durationMs,
                    estimate == null ? null : estimate.inputUsdPerMillionTokens(),
                    estimate == null ? null : estimate.outputUsdPerMillionTokens(),
                    estimate == null ? null : estimate.estimatedCostMicrousd()
            ));
        } catch (RuntimeException e) {
            log.warn("AI Gemini 성공 호출 이력 생성에 실패했습니다. analysisId={}, attemptNumber={}",
                    analysisId, attemptNumber, e);
        }
    }

    private void recordFailedCall(long analysisId, int attemptNumber, String errorCode,
                                  AudienceAnalysisLlmResponse.Usage usage, long durationMs) {
        try {
            var estimate = costCalculator.estimate(usage, costProperties).orElse(null);
            saveUsage(new AiLlmCallUsage(
                    analysisId, attemptNumber, safeModelName(llmClient.modelName()), AiLlmCallStatus.FAILED, errorCode,
                    usage == null ? null : usage.promptTokenCount(),
                    usage == null ? null : usage.outputTokenCount(),
                    usage == null ? null : usage.totalTokenCount(),
                    durationMs,
                    estimate == null ? null : estimate.inputUsdPerMillionTokens(),
                    estimate == null ? null : estimate.outputUsdPerMillionTokens(),
                    estimate == null ? null : estimate.estimatedCostMicrousd()
            ));
        } catch (RuntimeException e) {
            log.warn("AI Gemini 실패 호출 이력 생성에 실패했습니다. analysisId={}, attemptNumber={}",
                    analysisId, attemptNumber, e);
        }
    }

    /** 호출 이력은 관측용 부가 데이터다. 저장 장애가 분석 결과의 상태 전이를 방해하지 않는다. */
    private void saveUsage(AiLlmCallUsage usage) {
        if (usages == null) {
            return;
        }
        try {
            usages.save(usage);
        } catch (RuntimeException e) {
            log.warn("AI Gemini 호출 이력 저장에 실패했습니다. analysisId={}, attemptNumber={}",
                    usage.analysisId(), usage.attemptNumber(), e);
        }
    }

    private String safeModelName(String modelName) {
        return modelName == null || modelName.isBlank() || modelName.length() > 100 ? null : modelName;
    }

    private void waitBeforeRetry(GeminiAudienceAnalysisException failure) {
        Duration delay = retryDelay(failure);
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeminiAudienceAnalysisException(GeminiAudienceAnalysisErrorCode.INTERRUPTED, false, null,
                    "Gemini 분석 재시도 대기 중 인터럽트되었습니다.");
        }
    }

    private Duration retryDelay(GeminiAudienceAnalysisException failure) {
        Duration retryAfter = failure.retryAfter();
        if (retryAfter != null && !retryAfter.isNegative()) {
            return retryAfter.compareTo(retryProperties.maxRetryAfter()) > 0
                    ? retryProperties.maxRetryAfter() : retryAfter;
        }
        return retryProperties.retryDelay();
    }

    private void validateSourceMessages(List<AudienceMessage> candidates, AiAnalysisResult result) {
        Set<Long> candidateIds = new HashSet<>();
        for (AudienceMessage message : candidates) {
            candidateIds.add(message.messageId());
        }
        Set<Long> assignedIds = new HashSet<>();
        for (AiTopicResult topic : result.topics()) {
            for (long sourceMessageId : topic.sourceMessageIds()) {
                if (!candidateIds.contains(sourceMessageId)) {
                    throw new InvalidAiAnalysisResultException("분석 대상이 아닌 메시지를 참조했습니다.");
                }
                if (!assignedIds.add(sourceMessageId)) {
                    throw new InvalidAiAnalysisResultException("하나의 메시지는 하나의 주제 그룹에만 연결할 수 있습니다.");
                }
            }
        }
    }

    private String safeErrorMessage(Exception e) {
        if (e instanceof GeminiAudienceAnalysisException geminiException) {
            return geminiException.errorCode().name();
        }
        if (e instanceof InvalidAiAnalysisResultException) {
            return "INVALID_LLM_RESPONSE";
        }
        return "AI_ANALYSIS_PROCESSING_FAILED";
    }

    private static AiAnalysisCompletionService noOpCompletions(AiAnalysisRepository analyses) {
        return new AiAnalysisCompletionService(analyses, event -> { });
    }
}
