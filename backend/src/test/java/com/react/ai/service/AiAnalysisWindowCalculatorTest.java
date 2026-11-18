package com.react.ai.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AiAnalysisWindowCalculatorTest {
    private final AiAnalysisWindowCalculator calculator = new AiAnalysisWindowCalculator();
    private final LocalDateTime startedAt = LocalDateTime.of(2026, 11, 18, 10, 0, 7);

    @Test
    void startedAt을_기준으로_완전히_종료된_30초_구간만_계산한다() {
        var windows = calculator.completedWindows(startedAt, startedAt.plusSeconds(90));

        assertThat(windows).containsExactly(
                new AiAnalysisWindowCalculator.AnalysisWindow(startedAt, startedAt.plusSeconds(30)),
                new AiAnalysisWindowCalculator.AnalysisWindow(startedAt.plusSeconds(30), startedAt.plusSeconds(60)),
                new AiAnalysisWindowCalculator.AnalysisWindow(startedAt.plusSeconds(60), startedAt.plusSeconds(90))
        );
    }

    @Test
    void 아직_닫히지_않은_구간은_계산하지_않는다() {
        assertThat(calculator.completedWindows(startedAt, startedAt.plusSeconds(29))).isEmpty();

        var windows = calculator.completedWindows(startedAt, startedAt.plusSeconds(61));

        assertThat(windows).containsExactly(
                new AiAnalysisWindowCalculator.AnalysisWindow(startedAt, startedAt.plusSeconds(30)),
                new AiAnalysisWindowCalculator.AnalysisWindow(startedAt.plusSeconds(30), startedAt.plusSeconds(60))
        );
    }
}
