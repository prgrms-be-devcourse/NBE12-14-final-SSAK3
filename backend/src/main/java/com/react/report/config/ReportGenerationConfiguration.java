package com.react.report.config;

import com.react.report.service.ReportRecoveryService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;

@Configuration
public class ReportGenerationConfiguration {
    @Bean(defaultCandidate = false)
    ThreadPoolTaskExecutor reportReceiptExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("report-receipt-");
        return executor;
    }

    @Bean(defaultCandidate = false)
    ThreadPoolTaskExecutor reportGenerationExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("report-generation-");
        return executor;
    }

    @Bean
    @Profile("!test")
    ThreadPoolTaskScheduler reportRecoveryScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("report-recovery-");
        return scheduler;
    }

    @Bean
    @Profile("!test")
    ApplicationListener<ApplicationReadyEvent> startReportRecovery(
            ReportRecoveryService recovery, @Qualifier("reportRecoveryScheduler") ThreadPoolTaskScheduler scheduler,
            @Value("${app.ai.report.recovery-interval:30s}") Duration interval) {
        // 첫 실행은 서버가 준비되는 즉시 시작한다. 모델 호출은 별도 생성 풀에서 실행한다.
        return event -> scheduler.scheduleWithFixedDelay(recovery::recover, interval);
    }
}
