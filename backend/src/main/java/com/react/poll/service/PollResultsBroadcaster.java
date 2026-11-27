package com.react.poll.service;

import com.react.poll.dto.PollResultsUpdatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 응답 저장이 커밋된 뒤에 방 주인 강연자에게 갱신된 결과를 방송한다. 커밋 전에 보내면 롤백된 응답이
 * 화면에 남거나, 방송을 받고 바로 조회한 쪽이 새 응답을 못 볼 수 있다. 롤백되면 이 메서드는 실행되지 않는다.
 * 집계는 별도 스레드에서 읽는다. 커밋 직후에도 응답 저장 스레드는 DB 커넥션을 쥐고 있어, 같은 스레드에서 집계하면
 * 동시 응답이 커넥션 풀 크기에 닿을 때 모두 두 번째 커넥션을 기다리며 멈춘다.
 */
@Slf4j
@Component
public class PollResultsBroadcaster {
    private final PollService polls;
    private final SimpMessagingTemplate broker;
    private final Executor executor;

    public PollResultsBroadcaster(PollService polls, SimpMessagingTemplate broker,
                                  @Qualifier("pollResultsBroadcastExecutor") Executor executor) {
        this.polls = polls;
        this.broker = broker;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onResponseSaved(PollResponseSavedEvent event) {
        // 응답은 이미 저장됐다. 방송이 실패해도 청중의 응답이 실패하면 안 되므로 로그만 남기고 삼킨다.
        try {
            executor.execute(() -> broadcast(event));
        } catch (RejectedExecutionException e) {
            log.warn("투표 결과 방송 작업을 등록하지 못했습니다. roomId={}, pollId={}", event.roomId(), event.pollId(), e);
        }
    }

    private void broadcast(PollResponseSavedEvent event) {
        try {
            var result = polls.currentResults(event.roomId(), event.pollId());
            broker.convertAndSend("/topic/rooms/" + event.roomId() + "/polls/results",
                    PollResultsUpdatedEvent.of(result));
        } catch (Exception e) {
            log.warn("투표 결과 방송 실패: roomId={}, pollId={}", event.roomId(), event.pollId(), e);
        }
    }
}
