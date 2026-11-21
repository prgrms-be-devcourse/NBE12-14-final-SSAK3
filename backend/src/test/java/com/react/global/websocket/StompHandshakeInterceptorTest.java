package com.react.global.websocket;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 핸드셰이크에서 방별 참여자 쿠키만 골라 보관하는지 확인한다. */
class StompHandshakeInterceptorTest {
    private final StompHandshakeInterceptor interceptor = new StompHandshakeInterceptor();

    @Test
    @SuppressWarnings("unchecked")
    void 방별_참여자_쿠키만_방_ID_기준으로_보관한다() {
        var attributes = handshake(
                new Cookie("PARTICIPANT_SESSION_1", "token-1"),
                new Cookie("PARTICIPANT_SESSION_22", "token-22"),
                new Cookie("JSESSIONID", "host-session"),
                new Cookie("PARTICIPANT_SESSION_abc", "bad"),
                new Cookie("PARTICIPANT_SESSION_0", "zero"),
                new Cookie("PARTICIPANT_SESSION_", "empty"));

        Map<Long, String> tokens = (Map<Long, String>) attributes.get(StompHandshakeInterceptor.PARTICIPANT_TOKENS);

        assertThat(tokens).containsOnly(Map.entry(1L, "token-1"), Map.entry(22L, "token-22"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 쿠키가_없어도_연결은_허용하고_빈_맵을_보관한다() {
        var attributes = handshake();

        assertThat((Map<Long, String>) attributes.get(StompHandshakeInterceptor.PARTICIPANT_TOKENS)).isEmpty();
    }

    @Test
    void HTTP_세션이_없으면_핸드셰이크를_거절한다() {
        var request = new MockHttpServletRequest();
        Map<String, Object> attributes = new HashMap<>();

        boolean allowed = interceptor.beforeHandshake(
                new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                null,
                attributes
        );

        assertThat(allowed).isFalse();
    }

    @Test
    void HTTP_세션과_WebSocket_연결_관리자를_보관한다() {
        var attributes = handshake();

        assertThat(attributes)
                .containsKey(StompHandshakeInterceptor.HTTP_SESSION)
                .containsKey(StompHandshakeInterceptor.HTTP_SESSION_ID)
                .containsKey(HttpSessionWebSocketLinks.HANDSHAKE_ATTRIBUTE);

        assertThat(attributes.get(
                HttpSessionWebSocketLinks.HANDSHAKE_ATTRIBUTE
        )).isInstanceOf(HttpSessionWebSocketLinks.class);
    }

    private Map<String, Object> handshake(Cookie... cookies) {
        var request = new MockHttpServletRequest();

        // SCRUM-164: 핸드셰이크에 필요한 HTTP 세션 생성
        request.getSession(true);

        if (cookies.length > 0) {
            request.setCookies(cookies);
        }
        Map<String, Object> attributes = new HashMap<>();

        boolean allowed = interceptor.beforeHandshake(new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse()), null, attributes);

        assertThat(allowed).isTrue();
        return attributes;
    }
}