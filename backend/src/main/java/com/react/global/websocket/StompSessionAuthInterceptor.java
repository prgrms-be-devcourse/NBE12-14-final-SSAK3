package com.react.global.websocket;

import com.react.global.exception.BusinessException;
import com.react.room.service.LectureParticipantService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class StompSessionAuthInterceptor implements ChannelInterceptor {

    private static final String CONNECTED_HOST =
            "stompConnectedHost";

    private final LectureParticipantService participantService;

    @Override
    public Message<?> preSend(
            Message<?> message,
            MessageChannel channel
    ) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(
                        message,
                        StompHeaderAccessor.class
                );

        if (accessor == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();

        if (command == StompCommand.CONNECT) {
            authenticate(accessor);
        } else if (command == StompCommand.SUBSCRIBE
                || command == StompCommand.SEND) {
            validateConnection(accessor);
        }

        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        Map<String, Object> attributes =
                requireAttributes(accessor);

        HttpSession session = requireCurrentSession(attributes);

        String expectedToken = (String) attributes.get(
                StompHandshakeInterceptor.CSRF_TOKEN
        );

        String submittedToken =
                accessor.getFirstNativeHeader("X-CSRF-TOKEN");

        if (expectedToken == null
                || submittedToken == null
                || !Objects.equals(expectedToken, submittedToken)) {

            throw new AccessDeniedException(
                    "STOMP CSRF 토큰이 유효하지 않습니다."
            );
        }

        String currentHost = currentHost(session);

        String handshakeHost = (String) attributes.get(
                StompHandshakeInterceptor.HTTP_HOST_NAME
        );

        // 핸드셰이크 이후 로그인 계정이 변경되었는지 확인
        if (!Objects.equals(currentHost, handshakeHost)) {
            throw new AccessDeniedException(
                    "HTTP 세션의 인증 정보가 변경되었습니다."
            );
        }

        Authentication authentication;

        if (currentHost != null) {
            authentication =
                    UsernamePasswordAuthenticationToken.authenticated(
                            currentHost,
                            null,
                            List.of(
                                    new SimpleGrantedAuthority("ROLE_HOST")
                            )
                    );

            attributes.put(CONNECTED_HOST, currentHost);

        } else if (hasValidParticipant(attributes)) {
            String sessionId = accessor.getSessionId();

            authentication =
                    UsernamePasswordAuthenticationToken.authenticated(
                            "participant-ws:" + sessionId,
                            null,
                            List.of(
                                    new SimpleGrantedAuthority(
                                            "ROLE_PARTICIPANT"
                                    )
                            )
                    );

        } else {
            throw new AccessDeniedException(
                    "STOMP 연결 인증 정보가 없습니다."
            );
        }

        // 이후 STOMP 메시지와 개인 오류 큐에서 사용할 사용자
        accessor.setUser(authentication);
    }

    private void validateConnection(StompHeaderAccessor accessor) {
        Map<String, Object> attributes =
                requireAttributes(accessor);

        HttpSession session = requireCurrentSession(attributes);

        String connectedHost =
                (String) attributes.get(CONNECTED_HOST);

        String currentHost = currentHost(session);

        if (connectedHost != null) {
            if (!connectedHost.equals(currentHost)) {
                throw new AccessDeniedException(
                        "강연자 로그인 세션이 변경되었습니다."
                );
            }

            return;
        }

        // 청중으로 연결했다면 후속 요청에서도
        // 적어도 하나 이상의 유효한 참여 인증이 있어야 한다.
        if (currentHost != null || !hasValidParticipant(attributes)) {
            throw new AccessDeniedException(
                    "청중 인증이 만료되었거나 세션이 변경되었습니다."
            );
        }
    }

    private HttpSession requireCurrentSession(
            Map<String, Object> attributes
    ) {
        Object stored = attributes.get(
                StompHandshakeInterceptor.HTTP_SESSION
        );

        Object expectedId = attributes.get(
                StompHandshakeInterceptor.HTTP_SESSION_ID
        );

        if (!(stored instanceof HttpSession session)
                || !(expectedId instanceof String sessionId)) {
            throw new AccessDeniedException(
                    "HTTP 세션이 필요합니다."
            );
        }

        try {
            if (!sessionId.equals(session.getId())) {
                throw new AccessDeniedException(
                        "HTTP 세션이 변경되었습니다."
                );
            }

            return session;
        } catch (IllegalStateException exception) {
            throw new AccessDeniedException(
                    "HTTP 세션이 만료되었습니다.",
                    exception
            );
        }
    }

    private String currentHost(HttpSession session) {
        try {
            return StompHandshakeInterceptor.currentHostName(
                    session
            );
        } catch (IllegalStateException exception) {
            throw new AccessDeniedException(
                    "HTTP 세션이 만료되었습니다.",
                    exception
            );
        }
    }

    private boolean hasValidParticipant(
            Map<String, Object> attributes
    ) {
        Object stored = attributes.get(
                StompHandshakeInterceptor.PARTICIPANT_TOKENS
        );

        if (!(stored instanceof Map<?, ?> tokens)) {
            return false;
        }

        for (Map.Entry<?, ?> entry : tokens.entrySet()) {
            if (!(entry.getKey() instanceof Long roomId)
                    || !(entry.getValue() instanceof String token)) {
                continue;
            }

            try {
                participantService.me(roomId, token);
                return true;
            } catch (BusinessException ignored) {
                // 다른 방의 유효한 쿠키가 있을 수 있으므로 계속 확인
            }
        }

        return false;
    }

    private Map<String, Object> requireAttributes(
            StompHeaderAccessor accessor
    ) {
        Map<String, Object> attributes =
                accessor.getSessionAttributes();

        if (attributes == null) {
            throw new AccessDeniedException(
                    "WebSocket 세션 정보가 없습니다."
            );
        }

        return attributes;
    }
}
