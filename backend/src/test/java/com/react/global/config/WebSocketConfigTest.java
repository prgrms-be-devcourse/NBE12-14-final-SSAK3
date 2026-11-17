package com.react.global.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketConfigTest {

    private ChannelInterceptor interceptor;
    private MessageChannel channel;

    @BeforeEach
    void setUp() {
        WebSocketConfig config =
                new WebSocketConfig("http://localhost:5173");

        ChannelRegistration registration =
                mock(ChannelRegistration.class);

        config.configureClientInboundChannel(registration);

        ArgumentCaptor<ChannelInterceptor> captor =
                ArgumentCaptor.forClass(ChannelInterceptor.class);

        verify(registration).interceptors(captor.capture());

        interceptor = captor.getValue();
        channel = mock(MessageChannel.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/topic/rooms/1/chat",
            "/queue/test",
            "/user/queue/errors"
    })
    void 브로커_주소로_직접_SEND하면_차단한다(String destination) {
        Message<byte[]> message =
                stompMessage(StompCommand.SEND, destination);

        assertThrows(
                AccessDeniedException.class,
                () -> interceptor.preSend(message, channel)
        );
    }

    @Test
    void 앱_주소로_SEND하면_통과한다() {
        Message<byte[]> message =
                stompMessage(StompCommand.SEND, "/app/rooms/1/chat");

        assertSame(message, interceptor.preSend(message, channel));
    }

    @Test
    void SUBSCRIBE는_직접_SEND_차단_규칙의_대상이_아니다() {
        Message<byte[]> message =
                stompMessage(StompCommand.SUBSCRIBE, "/topic/rooms/1/chat");

        assertSame(message, interceptor.preSend(message, channel));
    }

    private Message<byte[]> stompMessage(
            StompCommand command,
            String destination
    ) {
        StompHeaderAccessor accessor =
                StompHeaderAccessor.create(command);

        accessor.setDestination(destination);
        accessor.setLeaveMutable(true);

        return MessageBuilder.createMessage(
                new byte[0],
                accessor.getMessageHeaders()
        );
    }
}