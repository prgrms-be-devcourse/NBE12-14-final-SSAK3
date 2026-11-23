package com.react.poll.dto;

import com.react.poll.entity.PollCloseReason;
import com.react.poll.entity.PollStatus;
import com.react.poll.entity.PollType;
import com.react.poll.entity.UnderstandingStatus;

import java.time.Instant;
import java.util.List;

/**
 * 투표 생성·마감 이벤트(청중과 강연자 모두 구독). 다른 STOMP 이벤트처럼 { "type": ..., "data": {...} } 형식이다.
 * 시각은 메시지 변환기가 Instant 를 숫자로 내보내지 않게 ISO-8601 UTC 문자열로 담는다.
 */
public record PollLifecycleEvent(String type, Object data) {
    public static final String CREATED = "POLL_CREATED";
    public static final String CLOSED = "POLL_CLOSED";

    public record Option(long optionId, String content, UnderstandingStatus understandingStatus) { }

    public record Created(long pollId, long roomId, PollType type, String question, PollStatus status,
            List<Option> options, String closesAt, String serverTime) { }

    /** 결과 수치는 담지 않는다. 집계는 강연자 전용 결과 이벤트와 결과 조회 API 로만 본다. */
    public record Closed(long pollId, long roomId, String closedAt, PollCloseReason closeReason) { }

    public static PollLifecycleEvent created(PollView poll) {
        List<Option> options = poll.options().stream()
                .map(option -> new Option(option.optionId(), option.content(), option.understandingStatus()))
                .toList();
        return new PollLifecycleEvent(CREATED, new Created(poll.pollId(), poll.roomId(), poll.type(),
                poll.question(), poll.status(), options, poll.closesAt().toString(), poll.serverTime().toString()));
    }

    public static PollLifecycleEvent closed(long roomId, long pollId, Instant closedAt, PollCloseReason reason) {
        return new PollLifecycleEvent(CLOSED, new Closed(pollId, roomId, closedAt.toString(), reason));
    }
}
