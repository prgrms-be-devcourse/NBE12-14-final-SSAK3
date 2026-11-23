package com.react.ai.service;

import com.react.ai.dto.AiAnalysisResult;
import com.react.ai.event.AiAnalysisCompletedDomainEvent;
import com.react.ai.repository.AiAnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** LLM 호출과 분리해 완료 저장과 완료 이벤트 등록만 짧은 트랜잭션으로 처리한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAnalysisCompletionService {
    private final AiAnalysisRepository analyses;
    private final ApplicationEventPublisher events;

    @Transactional
    public void completeWithTopics(long roomId, long analysisId, AiAnalysisResult result, String modelName) {
        analyses.saveCompleted(analysisId, result, modelName);
        publishCompleted(roomId, analysisId);
    }

    @Transactional
    public boolean completeEmpty(long roomId, long analysisId) {
        boolean completed = analyses.completeEmpty(analysisId);
        if (completed) {
            publishCompleted(roomId, analysisId);
        }
        return completed;
    }

    private void publishCompleted(long roomId, long analysisId) {
        try {
            events.publishEvent(new AiAnalysisCompletedDomainEvent(roomId, analysisId));
        } catch (RuntimeException e) {
            // 이벤트는 부가 알림이다. 예상 밖의 listener 오류가 분석 결과 저장을 롤백시키면 안 된다.
            log.warn("AI 분석 완료 이벤트 등록에 실패했습니다. roomId={}, analysisId={}", roomId, analysisId, e);
        }
    }
}
