package com.react.ai.service;

import com.react.ai.config.AiAnalysisSchedulerProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@RequiredArgsConstructor
public class AiAnalysisFinalizationScheduler {
    private static final Logger log = LoggerFactory.getLogger(AiAnalysisFinalizationScheduler.class);

    private final AiAnalysisFinalizationService finalizations;
    private final AiAnalysisSchedulerProperties properties;

    @Scheduled(fixedDelayString = "${app.ai.audience.scheduler.fixed-delay:5000}",
            scheduler = "aiFinalizationTaskScheduler")
    public void processPendingFinalizations() {
        try {
            finalizations.processPendingRooms(properties.maxFinalizationRoomsPerRun(),
                    properties.maxWindowsPerRun(), properties.finalizationLeaseDuration());
        } catch (RuntimeException e) {
            log.warn("AI 종료 finalization 스케줄러 실행에 실패했습니다.", e);
        }
    }
}
