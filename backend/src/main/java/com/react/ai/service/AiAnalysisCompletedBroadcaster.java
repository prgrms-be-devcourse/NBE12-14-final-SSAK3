package com.react.ai.service;

import com.react.ai.dto.AiAnalysisCompletedEvent;
import com.react.ai.event.AiAnalysisCompletedDomainEvent;
import com.react.ai.repository.AiAnalysisQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 커밋된 AI 분석만 HOST 전용 STOMP 채널로 알린다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiAnalysisCompletedBroadcaster {
    private final AiAnalysisQueryRepository queries;
    private final SimpMessagingTemplate broker;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void broadcast(AiAnalysisCompletedDomainEvent event) {
        try {
            var analysis = queries.findCompletedAnalysis(event.roomId(), event.analysisId());
            if (analysis.isEmpty() || analysis.get().completedAt() == null) {
                log.warn("커밋된 AI 분석 완료 이벤트의 대상을 찾지 못했습니다. roomId={}, analysisId={}",
                        event.roomId(), event.analysisId());
                return;
            }
            var groups = queries.findTopicGroups(java.util.List.of(event.analysisId()));
            broker.convertAndSend("/topic/rooms/" + event.roomId() + "/ai",
                    AiAnalysisCompletedEvent.of(event.roomId(), analysis.get(), groups));
        } catch (RuntimeException e) {
            // Simple broker 전송 실패는 이미 커밋된 분석 결과에 영향을 주지 않는다.
            log.warn("AI 분석 완료 WebSocket 이벤트 발행에 실패했습니다. roomId={}, analysisId={}",
                    event.roomId(), event.analysisId(), e);
        }
    }
}
