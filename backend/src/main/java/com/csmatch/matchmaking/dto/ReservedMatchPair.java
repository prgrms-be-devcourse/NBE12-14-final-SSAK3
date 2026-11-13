package com.csmatch.matchmaking.dto;

import com.csmatch.matchmaking.entity.MatchType;

public record ReservedMatchPair(
        long firstUserId,
        long secondUserId,
        MatchType matchType,
        String reservationToken
) {
}
