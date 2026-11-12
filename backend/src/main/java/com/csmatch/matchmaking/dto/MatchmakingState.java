package com.csmatch.matchmaking.dto;

import com.csmatch.matchmaking.entity.MatchType;
import com.csmatch.matchmaking.entity.MatchmakingStatus;

import java.time.Instant;

public record MatchmakingState(
        MatchmakingStatus status,
        MatchType matchType,
        Integer rating,
        Instant joinedAt,
        Long matchId
) {
    public static MatchmakingState idle() {
        return new MatchmakingState(MatchmakingStatus.IDLE, null, null, null, null);
    }
}
