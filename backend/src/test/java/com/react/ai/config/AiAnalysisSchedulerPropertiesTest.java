package com.react.ai.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiAnalysisSchedulerPropertiesTest {
    @Test
    void 강연방별_최대_구간_수는_양수여야_한다() {
        assertThatThrownBy(() -> new AiAnalysisSchedulerProperties(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiAnalysisSchedulerProperties(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void finalization_처리_한도와_lease는_양수여야_한다() {
        assertThatThrownBy(() -> new AiAnalysisSchedulerProperties(3, 0, Duration.ofSeconds(90)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiAnalysisSchedulerProperties(3, 5, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiAnalysisSchedulerProperties(3, 5, Duration.ofMillis(999)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
