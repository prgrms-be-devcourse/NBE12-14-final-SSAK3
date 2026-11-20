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
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.HashMap;
import java.util.Map;

@Component
public class StompHandshakeInterceptor implements HandshakeInterceptor {

    public static final String PARTICIPANT_TOKENS =
            "participantTokens";

    public static final String HTTP_SESSION =
            "stompHttpSession";

    public static final String HTTP_SESSION_ID =
            "stompHttpSessionId";

    public static final String HTTP_HOST_NAME =
            "stompHttpHostName";

    public static final String CSRF_TOKEN =
            "stompCsrfToken";

    private static final String COOKIE_PREFIX =
            "PARTICIPANT_SESSION_";

    private final HttpSessionCsrfTokenRepository csrfRepository =
            new HttpSessionCsrfTokenRepository();

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return false;
        }

        HttpServletRequest httpRequest =
                servletRequest.getServletRequest();

        // 기존 SCRUM-131의 방별 청중 쿠키 수집 기능 유지
        attributes.put(
                PARTICIPANT_TOKENS,
                participantTokens(httpRequest)
        );

        HttpSession session = httpRequest.getSession(false);

        // 실제 인증 거부는 STOMP CONNECT에서 수행한다.
        if (session == null) {
            return true;
        }

        try {
            attributes.put(HTTP_SESSION, session);
            attributes.put(HTTP_SESSION_ID, session.getId());

            String hostName = currentHostName(session);

            if (hostName != null) {
                attributes.put(HTTP_HOST_NAME, hostName);
            }

            CsrfToken csrfToken =
                    csrfRepository.loadToken(httpRequest);

            if (csrfToken != null) {
                attributes.put(
                        CSRF_TOKEN,
                        csrfToken.getToken()
                );
            }

            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
        // 별도 처리 없음
    }

    public static String currentHostName(HttpSession session) {
        Object stored = session.getAttribute(
                HttpSessionSecurityContextRepository
                        .SPRING_SECURITY_CONTEXT_KEY
        );

        if (!(stored instanceof SecurityContext context)) {
            return null;
        }

        Authentication authentication =
                context.getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }

        boolean isHost = authentication.getAuthorities()
                .stream()
                .anyMatch(authority ->
                        "ROLE_HOST".equals(authority.getAuthority())
                );

        return isHost ? authentication.getName() : null;
    }

    private Map<Long, String> participantTokens(
            HttpServletRequest request
    ) {
        Map<Long, String> tokens = new HashMap<>();

        Cookie[] cookies = request.getCookies();

        if (cookies == null) {
            return Map.of();
        }

        for (Cookie cookie : cookies) {
            String name = cookie.getName();

            if (!name.startsWith(COOKIE_PREFIX)) {
                continue;
            }

            String suffix =
                    name.substring(COOKIE_PREFIX.length());

            if (!suffix.matches("[1-9][0-9]{0,17}")) {
                continue;
            }

            try {
                long roomId = Long.parseLong(suffix);

                tokens.putIfAbsent(
                        roomId,
                        cookie.getValue()
                );
            } catch (NumberFormatException ignored) {
                // 숫자 범위를 벗어난 방 ID는 무시
            }
        }

        return Map.copyOf(tokens);
    }
}
