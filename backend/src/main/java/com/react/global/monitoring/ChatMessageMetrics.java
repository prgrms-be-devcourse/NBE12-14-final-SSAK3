package com.react.global.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 채팅 메시지 저장 및 방송 실패 횟수를 기록한다.
 * 계측 실패는 채팅 처리에 영향을 주지 않는다.
 */
@Slf4j
@Component
public class ChatMessageMetrics {

    private final Counter savedMessages;
    private final Counter broadcastFailures;

    public ChatMessageMetrics(MeterRegistry registry) {
        this.savedMessages = Counter.builder("react.chat.messages.saved")
                .description("저장에 성공한 채팅 메시지 수")
                .register(registry);

        this.broadcastFailures = Counter.builder("react.chat.broadcast.failures")
                .description("채팅 메시지 방송 실패 횟수")
                .register(registry);
    }

    public void recordSavedMessage() {
        increment(savedMessages);
    }

    public void recordBroadcastFailure() {
        increment(broadcastFailures);
    }

    private void increment(Counter counter) {
        try {
            counter.increment();
        } catch (RuntimeException e) {
            log.debug("채팅 메트릭 기록에 실패했습니다.", e);
        }
    }
}
