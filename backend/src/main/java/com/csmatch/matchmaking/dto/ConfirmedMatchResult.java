package com.csmatch.matchmaking.dto;

import com.csmatch.matchmaking.entity.MatchType;

public record ConfirmedMatchResult(
        long matchId,
        MatchType matchType,
        long firstUserId,
        long secondUserId
) {
}
