package com.react.poll.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 투표 생성·마감이 커밋된 뒤에 같은 강연방의 청중과 강연자에게 알린다. 청중 화면이 마감을 폴링 주기만큼
 * 늦게 알아채지 않게 하려는 것이며, 롤백되면 이 메서드는 실행되지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PollLifecycleBroadcaster {
    private final SimpMessagingTemplate broker;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChanged(PollLifecycleChangedEvent event) {
        // 투표는 이미 저장됐다. 방송이 실패해도 생성·마감이 실패하면 안 되므로 로그만 남기고 삼킨다.
        try {
            broker.convertAndSend("/topic/rooms/" + event.roomId() + "/polls", event.payload());
        } catch (Exception e) {
            log.warn("투표 상태 방송 실패: roomId={}, type={}", event.roomId(), event.payload().type(), e);
        }
    }
}
