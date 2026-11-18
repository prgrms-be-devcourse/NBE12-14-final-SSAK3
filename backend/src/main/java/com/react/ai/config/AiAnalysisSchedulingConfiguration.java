package com.react.ai.config;

import com.react.ai.service.AiAnalysisWindowCalculator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

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
}
