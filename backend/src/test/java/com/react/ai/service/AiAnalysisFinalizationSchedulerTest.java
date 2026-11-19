package com.react.ai.service;

import com.react.ai.config.AiAnalysisSchedulerProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiAnalysisFinalizationSchedulerTest {
    @Test
    void finalization은_실행별_강연방_한도와_기존_구간_한도를_사용한다() {
        AiAnalysisFinalizationService finalizations = mock(AiAnalysisFinalizationService.class);
        AiAnalysisSchedulerProperties properties =
                new AiAnalysisSchedulerProperties(3, 5, Duration.ofSeconds(90));
        AiAnalysisFinalizationScheduler scheduler = new AiAnalysisFinalizationScheduler(finalizations, properties);

        scheduler.processPendingFinalizations();

        verify(finalizations).processPendingRooms(5, 3, Duration.ofSeconds(90));
    }
}
