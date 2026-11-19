package com.react.ai.service;

import com.react.ai.config.AiAnalysisSchedulerProperties;
import com.react.ai.repository.AiAnalysisRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@RequiredArgsConstructor
public class AiAnalysisStaleRecoveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(AiAnalysisStaleRecoveryScheduler.class);

    private final AiAnalysisRepository analyses;
    private final AiAnalysisSchedulerProperties properties;

    @Scheduled(fixedDelayString = "${app.ai.audience.scheduler.recovery-fixed-delay:60000}",
            scheduler = "aiStaleRecoveryTaskScheduler")
    public void recoverStaleAnalyses() {
        try {
            int recovered = analyses.abortStaleInFlight(properties.staleAnalysisThreshold());
            if (recovered > 0) {
                log.warn("중단된 AI 분석 작업을 FAILED로 종결했습니다. count={}", recovered);
            }
        } catch (RuntimeException e) {
            log.warn("중단된 AI 분석 작업 복구에 실패했습니다.", e);
        }
    }
}
