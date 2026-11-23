package com.react.poll.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@Profile("!test")
public class PollSchedulingConfiguration {
    @Bean(name = "pollExpiryTaskScheduler")
    ThreadPoolTaskScheduler pollExpiryTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("poll-expiry-");
        return scheduler;
    }
}
