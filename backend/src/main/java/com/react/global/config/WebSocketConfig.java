package com.react.global.config;

import com.react.global.websocket.StompHandshakeInterceptor;
import com.react.global.websocket.StompSessionAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Arrays;

@Configuration
@EnableWebSocketMessageBroker
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompHandshakeInterceptor handshakeInterceptor;
    private final StompSessionAuthInterceptor authInterceptor;

    private String[] allowedOrigins;

    @Value("${app.cors.allowed-origins}")
    public void setAllowedOrigins(String origins) {
        this.allowedOrigins = Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toArray(String[]::new);
    }

    @Override
    public void registerStompEndpoints(
            StompEndpointRegistry registry
    ) {
        registry.addEndpoint("/ws")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(
            MessageBrokerRegistry registry
    ) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(
            ChannelRegistration registration
    ) {
        // 164: 연결 인증을 먼저 검사
        registration.interceptors(authInterceptor);

        // 기존 131의 브로커 직접 SEND 차단은 유지
        registration.interceptors(new ChannelInterceptor() {

            @Override
            public Message<?> preSend(
                    Message<?> message,
                    MessageChannel channel
            ) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(
                                message,
                                StompHeaderAccessor.class
                        );

                if (accessor == null
                        || accessor.getCommand() != StompCommand.SEND) {
                    return message;
                }

                String destination = accessor.getDestination();

                if (destination != null
                        && (destination.startsWith("/topic/")
                        || destination.startsWith("/queue/")
                        || destination.startsWith("/user/"))) {

                    throw new AccessDeniedException(
                            "브로커 주소로 직접 전송할 수 없습니다."
                    );
                }

                return message;
            }
        });
    }
}
