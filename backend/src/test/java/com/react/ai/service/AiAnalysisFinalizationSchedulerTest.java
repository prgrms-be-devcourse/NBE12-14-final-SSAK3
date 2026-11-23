package com.react.ai.service;

import com.react.ai.config.AiAnalysisSchedulerProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AiAnalysisFinalizationSchedulerTest {
    @Test
    void finalization은_실행별_강연방_한도와_기존_구간_한도를_사용한다() {
        AiAnalysisFinalizationService finalizations = mock(AiAnalysisFinalizationService.class);
        AiAnalysisSchedulerProperties properties =
                new AiAnalysisSchedulerProperties(3, 5, Duration.ofSeconds(90));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiAnalysisFinalizationScheduler scheduler = new AiAnalysisFinalizationScheduler(finalizations, properties,
                new AiAnalysisSchedulerMetrics(registry));
        when(finalizations.processPendingRooms(5, 3, Duration.ofSeconds(90)))
                .thenReturn(new AiAnalysisFinalizationService.FinalizationRun(2, 1, 0, 1, 3, 0));

        scheduler.processPendingFinalizations();

        verify(finalizations).processPendingRooms(5, 3, Duration.ofSeconds(90));
        assertThat(registry.get("react.ai.scheduler.finalization.executions").counter().count()).isEqualTo(1);
        assertThat(registry.get("react.ai.scheduler.finalization.target.rooms").summary().totalAmount()).isEqualTo(2);
        assertThat(registry.get("react.ai.scheduler.finalization.completed.rooms").summary().totalAmount()).isEqualTo(1);
        assertThat(registry.get("react.ai.scheduler.finalization.deferred.rooms").summary().totalAmount()).isEqualTo(1);
        assertThat(registry.get("react.ai.scheduler.finalization.failed.analysis.windows").summary().totalAmount())
                .isEqualTo(3);
        assertThat(registry.get("react.ai.scheduler.finalization.duration").timer().count()).isEqualTo(1);
    }
}
