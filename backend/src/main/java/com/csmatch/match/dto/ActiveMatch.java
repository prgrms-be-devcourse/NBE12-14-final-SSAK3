package com.csmatch.match.dto;

import com.csmatch.matchmaking.entity.MatchType;

public record ActiveMatch(
        long matchId,
        MatchType matchType
) {
}
