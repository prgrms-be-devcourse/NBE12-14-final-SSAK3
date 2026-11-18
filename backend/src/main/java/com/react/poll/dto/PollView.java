package com.react.poll.dto;

import com.react.poll.entity.PollCloseReason;
import com.react.poll.entity.PollStatus;
import com.react.poll.entity.PollType;

import java.time.Instant;
import java.util.List;

/**
 * 투표 한 건. status 는 마감 시각이 지난 투표를 CLOSED 로 계산한 값이다.
 * 청중 화면의 남은 시간은 closesAt − serverTime 으로 구하며 마감 판단은 서버가 한다.
 */
public record PollView(
        long pollId,
        long roomId,
        PollType type,
        String question,
        PollStatus status,
        List<PollOptionView> options,
        int durationSeconds,
        Instant createdAt,
        Instant closesAt,
        Instant closedAt,
        PollCloseReason closeReason,
        Instant serverTime
) { }
