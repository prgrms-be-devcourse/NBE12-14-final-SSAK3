package com.csmatch.matchmaking.dto;

import com.csmatch.matchmaking.entity.MatchType;
import java.time.Instant;

public record ReservationRecoveryData(
        String reservationToken,
        long firstUserId,
        long secondUserId,
        MatchType matchType,
        int firstRating,
        int secondRating,
        Instant firstJoinedAt,
        Instant secondJoinedAt,
        Instant expiresAt
) {
}
