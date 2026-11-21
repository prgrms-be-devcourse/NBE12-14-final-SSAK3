package com.react.global.config;

import com.react.global.websocket.StompSendAuthorizationInterceptor;
import com.react.global.websocket.StompSubscribeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP 방별 권한 검사 설정. 연결·브로커 설정은 WebSocketConfig 에 두고, 인증이 필요한 검문소만 이쪽에서 건다.
 * 같은 설정 인터페이스를 여러 개 둘 수 있어 WebSocketConfig 의 생성 방식을 바꾸지 않는다.
 */
@Configuration
@RequiredArgsConstructor
public class StompSecurityConfig
        implements WebSocketMessageBrokerConfigurer {

    private final StompSubscribeInterceptor subscribeInterceptor;
    private final StompSendAuthorizationInterceptor sendAuthorizationInterceptor;

    @Override
    public void configureClientInboundChannel(
            ChannelRegistration registration
    ) {
        registration.interceptors(
                subscribeInterceptor,
                sendAuthorizationInterceptor
        );
    }
}
