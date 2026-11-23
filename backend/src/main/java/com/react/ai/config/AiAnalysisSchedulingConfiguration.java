package com.react.ai.config;

import com.react.ai.service.AiAnalysisWindowCalculator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(AiAnalysisSchedulerProperties.class)
@Profile("!test")
public class AiAnalysisSchedulingConfiguration {
    @Bean
    Clock aiAnalysisClock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }

    @Bean
    AiAnalysisWindowCalculator aiAnalysisWindowCalculator() {
        return new AiAnalysisWindowCalculator();
    }

    @Bean(name = "aiLiveAnalysisTaskScheduler")
    ThreadPoolTaskScheduler aiLiveAnalysisTaskScheduler() {
        return taskScheduler("ai-live-analysis-");
    }

    @Bean(name = "aiFinalizationTaskScheduler")
    ThreadPoolTaskScheduler aiFinalizationTaskScheduler() {
        return taskScheduler("ai-finalization-");
    }

    @Bean(name = "aiStaleRecoveryTaskScheduler")
    ThreadPoolTaskScheduler aiStaleRecoveryTaskScheduler() {
        return taskScheduler("ai-stale-recovery-");
    }

    private ThreadPoolTaskScheduler taskScheduler(String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        return scheduler;
    }
}
