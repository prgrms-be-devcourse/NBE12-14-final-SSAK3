package com.react.global.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class HttpSessionWebSocketLinksTest {

    @Test
    void HTTP_세션이_무효화되면_WebSocket도_종료된다()
            throws Exception {

        MockHttpSession httpSession = new MockHttpSession();
        HttpSessionWebSocketLinks links =
                new HttpSessionWebSocketLinks();

        WebSocketSession socket =
                mock(WebSocketSession.class);

        when(socket.getId()).thenReturn("socket-1");
        when(socket.isOpen()).thenReturn(true);

        httpSession.setAttribute(
                HttpSessionWebSocketLinks.SESSION_ATTRIBUTE,
                links
        );

        assertThat(links.register(socket)).isTrue();

        // HTTP 세션이 무효화되면
        // HttpSessionBindingListener가 호출된다.
        httpSession.invalidate();

        verify(socket).close(CloseStatus.POLICY_VIOLATION);

        // 무효화된 연결 관리자는 소켓을 다시 등록할 수 없다.
        assertThat(links.register(socket)).isFalse();
    }

    @Test
    void HTTP_세션에서_관리자를_제거하면_소켓이_종료된다()
            throws Exception {

        MockHttpSession httpSession = new MockHttpSession();
        HttpSessionWebSocketLinks links =
                new HttpSessionWebSocketLinks();

        WebSocketSession socket =
                mock(WebSocketSession.class);

        when(socket.getId()).thenReturn("socket-2");
        when(socket.isOpen()).thenReturn(true);

        httpSession.setAttribute(
                HttpSessionWebSocketLinks.SESSION_ATTRIBUTE,
                links
        );

        assertThat(links.register(socket)).isTrue();

        // 재로그인 시 기존 관리자를 제거하는 동작
        httpSession.removeAttribute(
                HttpSessionWebSocketLinks.SESSION_ATTRIBUTE
        );

        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        assertThat(links.isActive("socket-2")).isFalse();
    }

    @Test
    void 청중_인증_만료_시각에_소켓이_종료된다()
            throws Exception {

        ThreadPoolTaskScheduler scheduler =
                new ThreadPoolTaskScheduler();

        scheduler.setPoolSize(1);
        scheduler.initialize();

        try {
            HttpSessionWebSocketLinks links =
                    new HttpSessionWebSocketLinks();

            WebSocketSession socket =
                    mock(WebSocketSession.class);

            when(socket.getId()).thenReturn("socket-3");
            when(socket.isOpen()).thenReturn(true);

            assertThat(links.register(socket)).isTrue();

            // 테스트에서는 실제 24시간 대신
            // 200밀리초 뒤 인증이 만료되는 상황을 만든다.
            links.expireAt(
                    "socket-3",
                    Instant.now().plusMillis(200),
                    scheduler
            );

            verify(socket, timeout(3000))
                    .close(CloseStatus.POLICY_VIOLATION);

            assertThat(links.isActive("socket-3")).isFalse();

        } finally {
            scheduler.shutdown();
        }
    }
}
