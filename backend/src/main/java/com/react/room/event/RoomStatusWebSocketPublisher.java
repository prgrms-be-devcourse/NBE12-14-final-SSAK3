package com.react.room.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RoomStatusWebSocketPublisher {

    private final ObjectProvider<SimpMessagingTemplate> messagingTemplate;

    // DB 트랜잭션이 정상 커밋된 뒤에만 상태 변경 이벤트 발행
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(RoomStatusChangedEvent event) {
        send(event.roomId(), "ROOM_STATUS_CHANGED", Map.of(
                "roomId", event.roomId(),
                "status", event.status().name(),
                "changedAt", event.changedAt()
        ));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(RoomDeletedEvent event) {
        send(event.roomId(), "ROOM_DELETED", Map.of("roomId", event.roomId()));
    }

    private void send(long roomId, String type, Map<String, Object> data) {
        SimpMessagingTemplate template = messagingTemplate.getIfAvailable();

        // 현재 운영 환경에서 WebSocket 설정이 비활성화된 경우
        if (template == null) {
            return;
        }

        try {
            template.convertAndSend("/topic/rooms/" + roomId + "/status", Map.of("type", type, "data", data));
        } catch (RuntimeException e) {
            log.warn(
                    "강연 상태 변경 WebSocket 이벤트 발행 실패. roomId={}",
                    roomId,
                    e
            );
        }
    }
}
