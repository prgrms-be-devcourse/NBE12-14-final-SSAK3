package com.react.global.config;

import com.react.global.websocket.HttpSessionWebSocketLinks;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

@Configuration(proxyBeanMethods = false)
public class WebSocketSessionLifecycleConfig
        implements WebSocketMessageBrokerConfigurer {

    /**
     * 청중 참여 세션 만료를 처리하는 전용 스케줄러.
     */
    @Bean("stompExpiryScheduler")
    public ThreadPoolTaskScheduler stompExpiryScheduler() {
        ThreadPoolTaskScheduler scheduler =
                new ThreadPoolTaskScheduler();

        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("stomp-expiry-");
        scheduler.setRemoveOnCancelPolicy(true);

        return scheduler;
    }

    /**
     * WebSocket 연결 생성과 종료를 감지한다.
     */
    @Override
    public void configureWebSocketTransport(
            WebSocketTransportRegistration registration
    ) {
        registration.addDecoratorFactory(
                handler -> new WebSocketHandlerDecorator(handler) {

                    @Override
                    public void afterConnectionEstablished(
                            WebSocketSession session
                    ) throws Exception {

                        HttpSessionWebSocketLinks links =
                                findLinks(session);

                        if (links != null && !links.register(session)) {
                            session.close(CloseStatus.POLICY_VIOLATION);
                            return;
                        }

                        try {
                            super.afterConnectionEstablished(session);
                        } catch (Exception exception) {
                            if (links != null) {
                                links.unregister(session.getId());
                            }

                            throw exception;
                        }
                    }

                    @Override
                    public void afterConnectionClosed(
                            WebSocketSession session,
                            CloseStatus closeStatus
                    ) throws Exception {

                        try {
                            super.afterConnectionClosed(
                                    session,
                                    closeStatus
                            );
                        } finally {
                            HttpSessionWebSocketLinks links =
                                    findLinks(session);

                            if (links != null) {
                                links.unregister(session.getId());
                            }
                        }
                    }
                }
        );
    }

    private static HttpSessionWebSocketLinks findLinks(
            WebSocketSession session
    ) {
        Object stored = session.getAttributes().get(
                HttpSessionWebSocketLinks.HANDSHAKE_ATTRIBUTE
        );

        return stored instanceof HttpSessionWebSocketLinks links
                ? links
                : null;
    }
}