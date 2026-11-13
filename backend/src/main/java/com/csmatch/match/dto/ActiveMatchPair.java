package com.csmatch.match.dto;

import java.util.Optional;

public record ActiveMatchPair(
        Optional<ActiveMatch> first,
        Optional<ActiveMatch> second
) {
    public ActiveMatchPair {
        first = first == null ? Optional.empty() : first;
        second = second == null ? Optional.empty() : second;
    }
}
