package com.csmatch.matchmaking.dto;

import java.time.Instant;

public record MatchCandidate(
        long userId,
        int rating,
        Instant joinedAt
) {
}
