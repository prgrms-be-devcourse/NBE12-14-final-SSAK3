package com.react.global.websocket;

import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StompSendAuthorizationInterceptorTest {

    private final StompActorResolver actors =
            mock(StompActorResolver.class);

    private final SimpMessagingTemplate broker =
            mock(SimpMessagingTemplate.class);

    private final Environment environment =
            mock(Environment.class);

    private final MessageChannel channel =
            mock(MessageChannel.class);

    private final StompSendAuthorizationInterceptor interceptor =
            new StompSendAuthorizationInterceptor(
                    actors,
                    broker,
                    environment
            );

    @Test
    void 권한이_있는_채팅_SEND는_전달한다() {
        Message<byte[]> message =
                sendMessage("/app/rooms/10/chat");

        when(actors.resolve(
                eq(10L),
                any(),
                anyMap()
        )).thenReturn(StompActor.host(1L));

        assertThat(
                interceptor.preSend(message, channel)
        ).isSameAs(message);

        verify(actors).resolve(
                eq(10L),
                any(),
                anyMap()
        );
    }

    @Test
    void 방별_권한이_없으면_SEND를_차단한다() {
        Message<byte[]> message =
                sendMessage("/app/rooms/10/chat");

        when(actors.resolve(
                eq(10L),
                any(),
                anyMap()
        )).thenThrow(
                new BusinessException(ErrorCode.FORBIDDEN)
        );

        // null 반환은 메시지가 다음 처리 단계로
        // 전달되지 않는다는 뜻이다.
        assertThat(
                interceptor.preSend(message, channel)
        ).isNull();

        verify(broker).convertAndSendToUser(
                eq("host@example.com"),
                eq("/queue/errors"),
                argThat(payload ->
                        payload instanceof java.util.Map<?, ?> map
                                && "FORBIDDEN".equals(
                                map.get("code")
                        )
                ),
                anyMap()
        );
    }

    @Test
    void 허용하지_않은_APP_SEND는_거절한다() {
        Message<byte[]> message =
                sendMessage("/app/rooms/10/unknown");

        assertThatThrownBy(() ->
                interceptor.preSend(message, channel)
        ).isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(actors);
    }

    private Message<byte[]> sendMessage(
            String destination
    ) {
        StompHeaderAccessor accessor =
                StompHeaderAccessor.create(
                        StompCommand.SEND
                );

        accessor.setDestination(destination);
        accessor.setSessionId("socket-test");
        accessor.setUser(() -> "host@example.com");
        accessor.setSessionAttributes(new HashMap<>());
        accessor.setLeaveMutable(true);

        return MessageBuilder.createMessage(
                new byte[0],
                accessor.getMessageHeaders()
        );
    }
}
