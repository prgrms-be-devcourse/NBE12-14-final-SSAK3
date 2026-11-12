package com.csmatch.matchmaking.service;

import java.time.Duration;
import java.time.Instant;

public final class RatingRangeCalculator {
    public static final int INITIAL_RANGE = 100;
    public static final int RANGE_INCREMENT = 100;
    public static final int RANGE_INCREMENT_INTERVAL_SECONDS = 10;
    public static final int MAX_RANGE = 500;

    private RatingRangeCalculator() {
    }

    public static int calculate(Instant joinedAt, Instant now) {
        if (joinedAt == null || now == null) {
            throw new IllegalArgumentException("Joined time and current time are required");
        }
        long waitedSeconds = Math.max(0, Duration.between(joinedAt, now).toSeconds());
        long increments = waitedSeconds / RANGE_INCREMENT_INTERVAL_SECONDS;
        long range = INITIAL_RANGE + increments * RANGE_INCREMENT;
        return (int) Math.min(range, MAX_RANGE);
    }
}
