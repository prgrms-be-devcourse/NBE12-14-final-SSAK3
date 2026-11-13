package com.csmatch.matchmaking.service;

import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RatingRangeCalculatorTest {
    private static final Instant JOINED_AT = Instant.parse("2026-11-12T00:00:00Z");

    @Test
    void startsAtOneHundredAndExpandsEveryTenSeconds() {
        assertThat(RatingRangeCalculator.calculate(JOINED_AT, JOINED_AT)).isEqualTo(100);
        assertThat(RatingRangeCalculator.calculate(JOINED_AT, JOINED_AT.plusSeconds(9))).isEqualTo(100);
        assertThat(RatingRangeCalculator.calculate(JOINED_AT, JOINED_AT.plusSeconds(10))).isEqualTo(200);
        assertThat(RatingRangeCalculator.calculate(JOINED_AT, JOINED_AT.plusSeconds(20))).isEqualTo(300);
        assertThat(RatingRangeCalculator.calculate(JOINED_AT, JOINED_AT.plusSeconds(39))).isEqualTo(400);
    }

    @Test
    void stopsExpandingAtFiveHundred() {
        assertThat(RatingRangeCalculator.calculate(JOINED_AT, JOINED_AT.plusSeconds(40))).isEqualTo(500);
        assertThat(RatingRangeCalculator.calculate(JOINED_AT, JOINED_AT.plusSeconds(3_600))).isEqualTo(500);
    }

    @Test
    void futureJoinedTimeDoesNotReduceInitialRange() {
        assertThat(RatingRangeCalculator.calculate(JOINED_AT, JOINED_AT.minusSeconds(1))).isEqualTo(100);
    }
}
