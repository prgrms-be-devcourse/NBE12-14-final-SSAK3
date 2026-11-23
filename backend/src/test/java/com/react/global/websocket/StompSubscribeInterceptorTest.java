package com.react.global.websocket;

import com.react.global.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StompSubscribeInterceptorTest {

    private final StompActorResolver actors = mock(StompActorResolver.class);
    private final MessageChannel channel = mock(MessageChannel.class);
    private final StompSubscribeInterceptor interceptor =
            new StompSubscribeInterceptor(actors);

    @ParameterizedTest
    @ValueSource(strings = {
            "/topic/rooms/10/chat",
            "/topic/rooms/10/status",
            "/topic/rooms/10/polls"
    })
    void 해당_방_청중은_공개_채널을_구독할_수_있다(String destination) {
        when(actors.resolveChat(eq(10L), any(SimpMessageHeaderAccessor.class)))
                .thenReturn(StompActor.participant(1L));

        Message<byte[]> message = subscribeMessage(destination, "PARTICIPANT");

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);

        verify(actors).resolveChat(
                eq(10L),
                any(SimpMessageHeaderAccessor.class)
        );
    }

    @Test
    void 강연자는_본인_방의_투표_결과를_구독할_수_있다() {
        when(actors.resolveChat(eq(10L), any(SimpMessageHeaderAccessor.class)))
                .thenReturn(StompActor.host(1L));

        Message<byte[]> message = subscribeMessage(
                "/topic/rooms/10/polls/results", "HOST"
        );

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void 청중은_투표_결과_채널을_구독할_수_없다() {
        when(actors.resolveChat(eq(10L), any(SimpMessageHeaderAccessor.class)))
                .thenReturn(StompActor.participant(2L));

        Message<byte[]> message = subscribeMessage(
                "/topic/rooms/10/polls/results", "PARTICIPANT"
        );

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 강연자는_본인_방의_AI_채널을_구독할_수_있다() {
        when(actors.resolveChat(eq(10L), any(SimpMessageHeaderAccessor.class)))
                .thenReturn(StompActor.host(1L));

        Message<byte[]> message = subscribeMessage(
                "/topic/rooms/10/ai", "HOST"
        );

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void 청중은_AI_채널을_구독할_수_없다() {
        when(actors.resolveChat(eq(10L), any(SimpMessageHeaderAccessor.class)))
                .thenReturn(StompActor.participant(2L));

        Message<byte[]> message = subscribeMessage(
                "/topic/rooms/10/ai", "PARTICIPANT"
        );

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 채팅_구독에서_요청한_역할을_권한_검사에_전달한다() {
        when(actors.resolveChat(eq(10L), any(SimpMessageHeaderAccessor.class)))
                .thenReturn(StompActor.participant(2L));

        Message<byte[]> message = subscribeMessage(
                "/topic/rooms/10/chat", "PARTICIPANT"
        );

        interceptor.preSend(message, channel);

        var captor = org.mockito.ArgumentCaptor.forClass(
                SimpMessageHeaderAccessor.class
        );

        verify(actors).resolveChat(eq(10L), captor.capture());

        assertThat(captor.getValue().getFirstNativeHeader("X-Chat-Role"))
                .isEqualTo("PARTICIPANT");
    }

    @Test
    void 개인_오류_큐는_구독할_수_있다() {
        Message<byte[]> message = subscribeMessage(
                "/user/queue/errors", null
        );

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        verifyNoInteractions(actors);
    }

    @Test
    void 허용되지_않은_구독_주소는_거절한다() {
        Message<byte[]> message = subscribeMessage(
                "/topic/rooms/10/secret", null
        );

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(actors);
    }

    private Message<byte[]> subscribeMessage(
            String destination, String role
    ) {
        StompHeaderAccessor accessor =
                StompHeaderAccessor.create(StompCommand.SUBSCRIBE);

        accessor.setDestination(destination);

        if (role != null) {
            accessor.setNativeHeader(StompActorResolver.CHAT_ROLE_HEADER, role);
        }

        accessor.setLeaveMutable(true);

        return MessageBuilder.createMessage(
                new byte[0],
                accessor.getMessageHeaders()
        );
    }
}