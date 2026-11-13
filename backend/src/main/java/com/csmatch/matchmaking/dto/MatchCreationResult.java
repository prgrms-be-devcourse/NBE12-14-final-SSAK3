package com.csmatch.matchmaking.dto;

public record MatchCreationResult(
        ReservedMatchPair reservation,
        long matchId
) {
}
