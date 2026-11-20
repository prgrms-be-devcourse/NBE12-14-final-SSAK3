package com.react.poll.service;

import com.react.poll.dto.PollResultsUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 응답 저장이 커밋된 뒤에 방 주인 강연자에게 갱신된 결과를 방송한다. 커밋 전에 보내면 롤백된 응답이
 * 화면에 남거나, 방송을 받고 바로 조회한 쪽이 새 응답을 못 볼 수 있다. 롤백되면 이 메서드는 실행되지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PollResultsBroadcaster {
    private final PollService polls;
    private final SimpMessagingTemplate broker;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onResponseSaved(PollResponseSavedEvent event) {
        // 응답은 이미 저장됐다. 방송이 실패해도 청중의 응답이 실패하면 안 되므로 로그만 남기고 삼킨다.
        try {
            var result = polls.currentResults(event.roomId(), event.pollId());
            broker.convertAndSend("/topic/rooms/" + event.roomId() + "/polls/results",
                    PollResultsUpdatedEvent.of(result));
        } catch (Exception e) {
            log.warn("투표 결과 방송 실패: roomId={}, pollId={}", event.roomId(), event.pollId(), e);
        }
    }
}
