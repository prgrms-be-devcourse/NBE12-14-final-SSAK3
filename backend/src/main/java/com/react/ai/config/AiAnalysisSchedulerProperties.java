package com.react.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

@ConfigurationProperties("app.ai.audience.scheduler")
public record AiAnalysisSchedulerProperties(int maxWindowsPerRun, int maxFinalizationRoomsPerRun,
                                            Duration finalizationLeaseDuration, Duration staleAnalysisThreshold) {
    @ConstructorBinding
    public AiAnalysisSchedulerProperties {
        if (maxWindowsPerRun <= 0) {
            throw new IllegalArgumentException("AI 분석 스케줄러의 강연방별 최대 구간 수는 1 이상이어야 합니다.");
        }
        if (maxFinalizationRoomsPerRun <= 0) {
            throw new IllegalArgumentException("AI finalization의 실행별 최대 강연방 수는 1 이상이어야 합니다.");
        }
        if (finalizationLeaseDuration == null || finalizationLeaseDuration.toSeconds() < 1) {
            throw new IllegalArgumentException("AI finalization lease 시간은 1초 이상이어야 합니다.");
        }
        if (staleAnalysisThreshold == null || staleAnalysisThreshold.toSeconds() < 1) {
            throw new IllegalArgumentException("AI stale 분석 작업 기준 시간은 1초 이상이어야 합니다.");
        }
    }

    public AiAnalysisSchedulerProperties(int maxWindowsPerRun, int maxFinalizationRoomsPerRun,
                                         Duration finalizationLeaseDuration) {
        this(maxWindowsPerRun, maxFinalizationRoomsPerRun, finalizationLeaseDuration, Duration.ofMinutes(2));
    }

    public AiAnalysisSchedulerProperties(int maxWindowsPerRun) {
        this(maxWindowsPerRun, 5, Duration.ofSeconds(90), Duration.ofMinutes(2));
    }
}
