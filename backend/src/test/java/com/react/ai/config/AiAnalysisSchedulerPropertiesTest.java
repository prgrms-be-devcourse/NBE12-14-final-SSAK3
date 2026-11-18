package com.react.ai.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiAnalysisSchedulerPropertiesTest {
    @Test
    void 강연방별_최대_구간_수는_양수여야_한다() {
        assertThatThrownBy(() -> new AiAnalysisSchedulerProperties(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiAnalysisSchedulerProperties(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
