package com.react.poll.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class PollBroadcastConfiguration {
    // 응답 저장 스레드가 DB 커넥션을 쥔 채 결과 집계용 커넥션을 또 기다리지 않도록, 결과 방송은 별도 풀에서 실행한다.
    @Bean(defaultCandidate = false)
    ThreadPoolTaskExecutor pollResultsBroadcastExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("poll-results-");
        return executor;
    }
}
