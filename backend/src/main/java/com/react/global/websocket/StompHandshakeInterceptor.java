package com.react.global.websocket;

import jakarta.servlet.http.Cookie;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket 연결 요청(핸드셰이크)에 실려 온 방별 참여자 쿠키를 세션 속성에 보관한다.
 * 쿠키는 이 순간에만 오고, 연결이 열린 뒤의 STOMP 메시지에는 붙지 않는다.
 * 청중은 방마다 쿠키가 따로라 연결 시점에는 어느 방에 쓸지 알 수 없으므로, 여기서는 보관만 하고
 * 방별 검증은 구독·전송 때 한다. 원문 쿠키 값은 로그에 남기지 않는다.
 */
public class StompHandshakeInterceptor implements HandshakeInterceptor {
    /** 세션 속성 키. 값은 방 ID → 그 방의 참여자 쿠키 값(Map&lt;Long, String&gt;)이다. */
    public static final String PARTICIPANT_TOKENS = "participantTokens";

    private static final String COOKIE_PREFIX = "PARTICIPANT_SESSION_";
    // 핸드 쉐이크 전에 호출
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        attributes.put(PARTICIPANT_TOKENS, participantTokens(request));
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
    }

    private static Map<Long, String> participantTokens(ServerHttpRequest request) {
        Map<Long, String> tokens = new HashMap<>();
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return tokens;
        }
        Cookie[] cookies = servletRequest.getServletRequest().getCookies();
        if (cookies == null) {
            return tokens;
        }
        for (Cookie cookie : cookies) {
            String name = cookie.getName();
            if (!name.startsWith(COOKIE_PREFIX)) {
                continue;
            }
            String suffix = name.substring(COOKIE_PREFIX.length());
            // 숫자로 된 방 ID 만 받고, 같은 방 쿠키가 여러 개면 먼저 온 것을 쓴다.
            if (suffix.matches("[1-9][0-9]{0,17}")) {
                tokens.putIfAbsent(Long.valueOf(suffix), cookie.getValue());
            }
        }
        return tokens;
    }
}
