package com.react.global.websocket;

import com.react.chat.dto.ChatErrorEvent;
import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

import java.util.HashMap;
import java.util.UUID;

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
            new StompSendAuthorizationInterceptor(actors, broker, environment);

    @Test
    void 권한이_있는_채팅_SEND는_전달한다() {
        Message<byte[]> message =
                sendMessage("/app/rooms/10/chat", "HOST", null);

        when(actors.resolveChat(eq(10L), any(SimpMessageHeaderAccessor.class)))
                .thenReturn(StompActor.host(1L));

        assertThat(interceptor.preSend(message, channel))
                .isSameAs(message);

        verify(actors).resolveChat(
                eq(10L),
                any(SimpMessageHeaderAccessor.class)
        );
    }

    @Test
    void 청중_역할을_선택한_SEND는_해당_역할로_검사한다() {
        Message<byte[]> message =
                sendMessage("/app/rooms/10/chat", "PARTICIPANT", null);

        when(actors.resolveChat(eq(10L), any(SimpMessageHeaderAccessor.class)))
                .thenReturn(StompActor.participant(2L));

        assertThat(interceptor.preSend(message, channel))
                .isSameAs(message);

        var captor = org.mockito.ArgumentCaptor.forClass(
                SimpMessageHeaderAccessor.class
        );

        verify(actors).resolveChat(eq(10L), captor.capture());

        assertThat(captor.getValue().getFirstNativeHeader("X-Chat-Role"))
                .isEqualTo("PARTICIPANT");
    }

    @Test
    void 방별_권한이_없으면_SEND를_차단하고_attempt를_반환한다() {
        String attempt = UUID.randomUUID().toString();

        Message<byte[]> message =
                sendMessage("/app/rooms/10/chat", "PARTICIPANT", attempt);

        when(actors.resolveChat(eq(10L), any(SimpMessageHeaderAccessor.class)))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

        // null 반환은 메시지가 다음 처리 단계로 전달되지 않는다는 뜻이다.
        assertThat(interceptor.preSend(message, channel)).isNull();

        verify(broker).convertAndSendToUser(
                eq("host@example.com"),
                eq("/queue/errors"),
                eq(ChatErrorEvent.of(ErrorCode.FORBIDDEN, attempt)),
                anyMap()
        );
    }

    @Test
    void 잘못된_attempt는_오류에_포함하지_않는다() {
        Message<byte[]> message =
                sendMessage("/app/rooms/10/chat", null, "invalid-attempt");

        when(actors.resolveChat(eq(10L), any(SimpMessageHeaderAccessor.class)))
                .thenThrow(new BusinessException(ErrorCode.UNAUTHENTICATED));

        assertThat(interceptor.preSend(message, channel)).isNull();

        verify(broker).convertAndSendToUser(
                eq("host@example.com"),
                eq("/queue/errors"),
                eq(ChatErrorEvent.of(ErrorCode.UNAUTHENTICATED, null)),
                anyMap()
        );
    }

    @Test
    void 허용하지_않은_APP_SEND는_거절한다() {
        Message<byte[]> message =
                sendMessage("/app/rooms/10/unknown", null, null);

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(actors);
        verifyNoInteractions(broker);
    }

    private Message<byte[]> sendMessage(
            String destination, String role, String attempt
    ) {
        StompHeaderAccessor accessor =
                StompHeaderAccessor.create(StompCommand.SEND);

        accessor.setDestination(destination);
        accessor.setSessionId("socket-test");
        accessor.setUser(() -> "host@example.com");
        accessor.setSessionAttributes(new HashMap<>());

        if (role != null) {
            accessor.setNativeHeader(StompActorResolver.CHAT_ROLE_HEADER, role);
        }

        if (attempt != null) {
            accessor.setNativeHeader("X-Chat-Attempt", attempt);
        }

        accessor.setLeaveMutable(true);

        return MessageBuilder.createMessage(
                new byte[0],
                accessor.getMessageHeaders()
        );
    }
}