package com.react.poll.dto;

import com.react.poll.entity.PollStatus;
import com.react.poll.entity.PollType;

import java.time.Instant;
import java.util.List;

/**
 * 투표 결과(강연방 소유자 전용). 응답자 수는 선택지별 인원의 합이고 미응답은 전체에서 응답을 뺀 수라,
 * 인원 합계가 항상 맞는다. 미응답은 어떤 선택지로도 세지 않으며 청중 개개인의 선택은 담지 않는다.
 */
public record PollResultView(
        long pollId,
        PollType type,
        PollStatus status,
        Instant closesAt,
        long totalParticipants,
        long respondedParticipants,
        long unrespondedParticipants,
        double responseRate,
        List<PollOptionResultView> options,
        Instant calculatedAt
) { }
