package com.csmatch.global.config;

import com.csmatch.global.websocket.AccessTokenVerifier;
import com.csmatch.global.websocket.MatchWebSocketHandler;
import com.csmatch.global.websocket.WebSocketConnections;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@EnableScheduling
public class WebSocketConfiguration {
    @Bean
    WebSocketConnections webSocketConnections(ObjectMapper mapper, ApplicationEventPublisher events) {
        return new WebSocketConnections(mapper, events, System::nanoTime);
    }

    @Bean
    MatchWebSocketHandler matchWebSocketHandler(ObjectMapper mapper,
            AccessTokenVerifier verifier, WebSocketConnections connections) {
        return new MatchWebSocketHandler(mapper, verifier,
                connections, Clock.systemUTC());
    }

    @Bean
    WebSocketConfigurer webSocketConfigurer(MatchWebSocketHandler handler,
            @Value("${app.websocket.allowed-origins:}") String[] allowedOrigins) {
        // 미설정 시 Spring 기본 정책인 동일 Origin만 허용한다.
        return registry -> registry.addHandler(handler, "/ws").setAllowedOrigins(allowedOrigins);
    }
}
