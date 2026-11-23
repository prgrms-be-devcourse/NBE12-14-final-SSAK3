package com.react.poll.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/** 투표 결과 갱신 이벤트(방 주인 강연자 전용). 다른 STOMP 이벤트처럼 { "type": ..., "data": {...} } 형식이다. */
public record PollResultsUpdatedEvent(String type, Data data) {
    public static final String TYPE = "POLL_RESULTS_UPDATED";

    /** counts 의 키는 선택지 번호이고, 응답이 없는 선택지도 0 으로 담는다. */
    public record Data(long pollId, long totalParticipants, long respondedParticipants,
            long unrespondedParticipants, Map<Long, Long> counts) { }

    public static PollResultsUpdatedEvent of(PollResultView result) {
        Map<Long, Long> counts = new LinkedHashMap<>();
        result.options().forEach(option -> counts.put(option.optionId(), option.count()));
        return new PollResultsUpdatedEvent(TYPE, new Data(result.pollId(), result.totalParticipants(),
                result.respondedParticipants(), result.unrespondedParticipants(), counts));
    }
}
