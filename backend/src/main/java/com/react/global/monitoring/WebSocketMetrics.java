package com.react.global.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Component;

/**
 * 현재 연결된 STOMP 세션 수를 측정한다.
 */
@Component
@RequiredArgsConstructor
public class WebSocketMetrics implements MeterBinder {

    private final SimpUserRegistry userRegistry;

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(
                        "react.websocket.sessions.active",
                        userRegistry,
                        users -> users.getUsers().stream()
                                .mapToInt(user -> user.getSessions().size())
                                .sum()
                )
                .description("현재 연결된 STOMP 세션 수")
                .register(registry);
    }
}
