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

    @Test
    void 종료_시_정규_구간과_30초_미만_잔여_구간을_함께_계산한다() {
        var endedAt = startedAt.plusSeconds(75);

        assertThat(calculator.finalizationWindows(startedAt, endedAt)).containsExactly(
                new AiAnalysisWindowCalculator.AnalysisWindow(startedAt, startedAt.plusSeconds(30)),
                new AiAnalysisWindowCalculator.AnalysisWindow(startedAt.plusSeconds(30), startedAt.plusSeconds(60)),
                new AiAnalysisWindowCalculator.AnalysisWindow(startedAt.plusSeconds(60), endedAt)
        );
    }

    @Test
    void 정확히_30초_경계에서_종료하면_잔여_구간을_만들지_않는다() {
        assertThat(calculator.finalizationWindows(startedAt, startedAt.plusSeconds(60))).containsExactly(
                new AiAnalysisWindowCalculator.AnalysisWindow(startedAt, startedAt.plusSeconds(30)),
                new AiAnalysisWindowCalculator.AnalysisWindow(startedAt.plusSeconds(30), startedAt.plusSeconds(60))
        );
    }

    @Test
    void 삼십초_미만_강연은_하나의_잔여_구간으로_계산한다() {
        assertThat(calculator.finalizationWindows(startedAt, startedAt.plusSeconds(29))).containsExactly(
                new AiAnalysisWindowCalculator.AnalysisWindow(startedAt, startedAt.plusSeconds(29))
        );
    }

    @Test
    void 시작과_종료_시각이_같은_빈_강연은_분석_구간을_만들지_않는다() {
        assertThat(calculator.finalizationWindows(startedAt, startedAt)).isEmpty();
    }
}
