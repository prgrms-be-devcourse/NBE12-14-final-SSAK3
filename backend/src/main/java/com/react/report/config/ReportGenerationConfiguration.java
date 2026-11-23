package com.react.report.config;

import com.react.report.service.ReportGenerationWorker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

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
        executor.setThreadNamePrefix("report-generation-");
        return executor;
    }

    @Bean
    @Profile("!test")
    ApplicationListener<ApplicationReadyEvent> recoverReportsOnStartup(
            ReportGenerationWorker worker, @Qualifier("reportGenerationExecutor") Executor executor) {
        return event -> executor.execute(worker::recover);
    }
}
