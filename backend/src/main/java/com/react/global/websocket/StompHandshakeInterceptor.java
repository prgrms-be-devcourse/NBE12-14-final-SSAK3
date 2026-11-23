package com.react.global.websocket;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * WebSocket 연결 요청(핸드셰이크)에 실려 온 방별 참여자 쿠키를 세션 속성에 보관한다.
 * 쿠키는 이 순간에만 오고, 연결이 열린 뒤의 STOMP 메시지에는 붙지 않는다.
 * 청중은 방마다 쿠키가 따로라 연결 시점에는 어느 방에 쓸지 알 수 없으므로, 여기서는 보관만 하고
 * 방별 검증은 구독·전송 때 한다. 원문 쿠키 값은 로그에 남기지 않는다.
 */
public class StompHandshakeInterceptor implements HandshakeInterceptor {
    /** 세션 속성 키. 값은 방 ID → 그 방의 참여자 쿠키 값(Map&lt;Long, String&gt;)이다. */
    public static final String PARTICIPANT_TOKENS = "participantTokens";

    public static final String HTTP_SESSION = "stompHttpSession";
    public static final String HTTP_SESSION_ID = "stompHttpSessionId";
    public static final String HTTP_HOST_NAME = "stompHttpHostName";
    public static final String CSRF_TOKEN = "stompCsrfToken";

    private static final String COOKIE_PREFIX = "PARTICIPANT_SESSION_";

    private final HttpSessionCsrfTokenRepository csrfRepository =
            new HttpSessionCsrfTokenRepository();

    // 핸드 쉐이크 전에 호출
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return false;
        }

        HttpServletRequest httpRequest = servletRequest.getServletRequest();

        // 기존 SCRUM-131의 방별 청중 쿠키 수집 기능 유지
        attributes.put(PARTICIPANT_TOKENS, participantTokens(request));

        HttpSession session = httpRequest.getSession(false);

        // HTTP 세션이 없으면 핸드셰이크를 거절한다.
        // 실제 사용자 인증은 다음 단계의 STOMP CONNECT에서 검증한다.
        if (session == null) {
            return false;
        }

        try {
            synchronized (session) {
                String requestHost = hostName(httpRequest.getUserPrincipal());
                String sessionHost = currentHostName(session);

                // HTTP 요청과 세션에 저장된 강연자 인증 정보가 일치해야 한다.
                if (!Objects.equals(requestHost, sessionHost)) {
                    return false;
                }

                HttpSessionWebSocketLinks links = findOrCreateLinks(session);
                if (links == null) {
                    return false;
                }

                attributes.put(HTTP_SESSION, session);
                attributes.put(HTTP_SESSION_ID, session.getId());

                if (sessionHost != null) {
                    attributes.put(HTTP_HOST_NAME, sessionHost);
                }

                CsrfToken csrfToken = csrfRepository.loadToken(httpRequest);
                if (csrfToken != null) {
                    attributes.put(CSRF_TOKEN, csrfToken.getToken());
                }

                // 실제 소켓 등록은 WebSocketSessionLifecycleConfig에서 수행한다.
                attributes.put(
                        HttpSessionWebSocketLinks.HANDSHAKE_ATTRIBUTE,
                        links
                );

                return true;
            }
        } catch (IllegalStateException exception) {
            // 핸드셰이크 중 HTTP 세션이 무효화된 경우
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    private static HttpSessionWebSocketLinks findOrCreateLinks(HttpSession session) {
        Object existing = session.getAttribute(
                HttpSessionWebSocketLinks.SESSION_ATTRIBUTE
        );

        if (existing instanceof HttpSessionWebSocketLinks links) {
            return links;
        }

        if (existing != null) {
            return null;
        }

        HttpSessionWebSocketLinks links = new HttpSessionWebSocketLinks();
        session.setAttribute(
                HttpSessionWebSocketLinks.SESSION_ATTRIBUTE,
                links
        );

        return links;
    }

    public static String currentHostName(HttpSession session) {
        Object stored = session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY
        );

        if (!(stored instanceof SecurityContext context)) {
            return null;
        }

        return hostName(context.getAuthentication());
    }

    private static String hostName(Principal principal) {
        if (!(principal instanceof Authentication authentication)
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }

        boolean isHost = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_HOST".equals(authority.getAuthority()));

        return isHost ? authentication.getName() : null;
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