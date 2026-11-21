package com.react.global.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class HttpSessionWebSocketLinksTest {

    @Test
    void WebSocket을_등록하면_활성_연결로_확인할_수_있다() {
        HttpSessionWebSocketLinks links =
                new HttpSessionWebSocketLinks();

        WebSocketSession socket = socket("ws-1");

        assertThat(links.register(socket)).isTrue();
        assertThat(links.isActive("ws-1")).isTrue();
    }

    @Test
    void 전체_연결을_종료하면_등록된_소켓도_닫힌다()
            throws Exception {

        HttpSessionWebSocketLinks links =
                new HttpSessionWebSocketLinks();

        WebSocketSession socket = socket("ws-1");

        links.register(socket);
        links.closeAll();

        verify(socket).close(CloseStatus.POLICY_VIOLATION);

        assertThat(links.isActive("ws-1")).isFalse();
    }

    @Test
    void 종료된_연결_관리자에는_새_소켓을_등록할_수_없다() {
        HttpSessionWebSocketLinks links =
                new HttpSessionWebSocketLinks();

        links.closeAll();

        assertThat(links.register(socket("ws-1"))).isFalse();
    }

    @Test
    void 정상_연결_해제는_다른_연결을_종료하지_않는다()
            throws Exception {

        HttpSessionWebSocketLinks links =
                new HttpSessionWebSocketLinks();

        WebSocketSession first = socket("ws-1");
        WebSocketSession second = socket("ws-2");

        links.register(first);
        links.register(second);

        links.unregister("ws-1");

        assertThat(links.isActive("ws-1")).isFalse();
        assertThat(links.isActive("ws-2")).isTrue();

        verify(first, never()).close(any(CloseStatus.class));
        verify(second, never()).close(any(CloseStatus.class));
    }

    @Test
    void HTTP_세션에서_제거되면_WebSocket을_종료한다()
            throws Exception {

        HttpSessionWebSocketLinks links =
                new HttpSessionWebSocketLinks();

        WebSocketSession socket = socket("ws-1");

        links.register(socket);

        links.valueUnbound(null);

        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        assertThat(links.isActive("ws-1")).isFalse();
    }

    private WebSocketSession socket(String id) {
        WebSocketSession socket =
                mock(WebSocketSession.class);

        when(socket.getId()).thenReturn(id);
        when(socket.isOpen()).thenReturn(true);

        return socket;
    }
}