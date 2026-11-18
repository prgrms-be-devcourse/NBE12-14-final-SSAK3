package com.react.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.ai.audience.scheduler")
public record AiAnalysisSchedulerProperties(int maxWindowsPerRun) {
    public AiAnalysisSchedulerProperties {
        if (maxWindowsPerRun <= 0) {
            throw new IllegalArgumentException("AI 분석 스케줄러의 강연방별 최대 구간 수는 1 이상이어야 합니다.");
        }
    }
}
