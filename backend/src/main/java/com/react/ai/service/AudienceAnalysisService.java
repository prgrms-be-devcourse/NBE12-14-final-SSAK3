package com.react.ai.service;

import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.dto.AiAnalysisProcessResult;
import com.react.ai.dto.AiAnalysisResult;
import com.react.ai.dto.AiTopicResult;
import com.react.ai.dto.AudienceAnalysisLlmResponse;
import com.react.ai.dto.AudienceMessage;
import com.react.ai.exception.InvalidAiAnalysisResultException;
import com.react.ai.repository.AiAnalysisRepository;
import com.react.ai.repository.AudienceMessageRepository;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 스케줄러와 외부 LLM 제공자에 의존하지 않는 분석 처리 코어다.
 */
public class AudienceAnalysisService {
    private final AiAnalysisRepository analyses;
    private final AudienceMessageRepository messages;
    private final AudienceAnalysisLlmClient llmClient;
    private final AiAnalysisJsonParser jsonParser;

    public AudienceAnalysisService(AiAnalysisRepository analyses, AudienceMessageRepository messages,
                                   AudienceAnalysisLlmClient llmClient, AiAnalysisJsonParser jsonParser) {
        this.analyses = analyses;
        this.messages = messages;
        this.llmClient = llmClient;
        this.jsonParser = jsonParser;
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
                if (!analyses.completeEmpty(acquired.analysis().id())) {
                    throw new IllegalStateException("빈 분석 구간을 완료 상태로 변경할 수 없습니다.");
                }
                return new AiAnalysisProcessResult(acquired.analysis().id(), AiAnalysisStatus.COMPLETED, true, 0);
            }

            AudienceAnalysisLlmResponse response = llmClient.analyze(candidates);
            if (response == null || response.content() == null || response.modelName() == null
                    || response.modelName().isBlank() || response.modelName().length() > 100) {
                throw new InvalidAiAnalysisResultException("LLM 응답에 모델 정보 또는 결과가 없습니다.");
            }
            AiAnalysisResult result = jsonParser.parse(response.content());
            validateSourceMessages(candidates, result);
            analyses.saveCompleted(acquired.analysis().id(), result, response.modelName());
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
        if (e instanceof InvalidAiAnalysisResultException) {
            return "INVALID_LLM_RESPONSE";
        }
        return "AI_ANALYSIS_PROCESSING_FAILED";
    }
}
