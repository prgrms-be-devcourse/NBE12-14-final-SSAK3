package com.react.report.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.report.repository.ReportAlertRepository;
import com.react.report.service.ReportAlertDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;

@Slf4j
@Configuration
@Profile("!test")
public class ReportAlertConfiguration {
    @Bean
    ThreadPoolTaskScheduler reportAlertScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("report-alert-");
        return scheduler;
    }

    @Bean
    ApplicationListener<ApplicationReadyEvent> startReportAlerts(
            ReportAlertRepository alerts, ObjectMapper json,
            @Qualifier("reportAlertScheduler") ThreadPoolTaskScheduler scheduler,
            @Value("${app.ai.report.alert.webhook-url}") String url) {
        return event -> {
            if (url.isBlank()) {
                log.info("보고서 개발자 알림 수신 주소가 없습니다. 실패 알림은 DB에 보관합니다.");
                return;
            }
            try {
                var dispatcher = new ReportAlertDispatcher(alerts, json, url);
                scheduler.scheduleWithFixedDelay(dispatcher::deliver, Duration.ofSeconds(30));
            } catch (IllegalArgumentException invalidConfig) {
                // Webhook URLs contain credentials. Keep them out of error messages and logs.
                log.error("보고서 개발자 알림 채널 설정을 확인하세요. 실패 알림은 DB에 보관합니다.");
            }
        };
    }
}
