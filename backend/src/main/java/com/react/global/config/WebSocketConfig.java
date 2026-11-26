package com.react.global.config;

import com.react.global.websocket.StompHandshakeInterceptor;
import com.react.global.websocket.StompSessionAuthInterceptor;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Arrays;

@Configuration
@EnableWebSocketMessageBroker
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final long HEARTBEAT_MS = 10_000;
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;
    private static final int OUTBOUND_QUEUE_CAPACITY = 50_000;

    private final String[] allowedOrigins;
    private final StompSessionAuthInterceptor authInterceptor;

    public WebSocketConfig(
            @Value("${app.cors.allowed-origins}") String origins,
            StompSessionAuthInterceptor authInterceptor
    ) {
        this.allowedOrigins = Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toArray(String[]::new);

        this.authInterceptor = authInterceptor;
    }

    // 클라이언트가 최초 연결할 주소
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .addInterceptors(new StompHandshakeInterceptor())
                .setAllowedOriginPatterns(allowedOrigins);
    }

    // STOMP 메시지 경로 설정
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        // 하트비트로 끊긴 연결을 정리해 죽은 연결에 계속 보내는 일을 막는다.
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{HEARTBEAT_MS, HEARTBEAT_MS})
                .setTaskScheduler(heartbeatScheduler());
        registry.setUserDestinationPrefix("/user");
    }

    private static ThreadPoolTaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }

    // 느린 클라이언트의 전송 버퍼가 무한히 쌓여 힙을 채우지 않게, 한도를 넘는 연결만 끊는다.
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setSendTimeLimit(SEND_TIME_LIMIT_MS)
                .setSendBufferSizeLimit(SEND_BUFFER_LIMIT_BYTES);
    }

    // 브로드캐스트가 밀릴 때 outbound 큐가 끝없이 커지지 않게 상한을 둔다.
    // 큐가 차면 스레드를 늘리지 않고 거절하며, 거절된 전송은 브로드캐스트 실패로 집계되고 내역 재조회로 복구한다.
    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        int threads = Runtime.getRuntime().availableProcessors() * 2;
        registration.taskExecutor()
                .corePoolSize(threads)
                .maxPoolSize(threads)
                .queueCapacity(OUTBOUND_QUEUE_CAPACITY);
    }

    // 클라이언트가 브로커 주소로 직접 메시지를 보내는 행위 차단
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // STOMP CONNECT 인증과 SUBSCRIBE·SEND의 세션 유효성 검사
        registration.interceptors(authInterceptor);

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
                            "브로커 주소로 직접 메시지를 전송할 수 없습니다."
                    );
                }

                return message;
            }
        });
    }
}