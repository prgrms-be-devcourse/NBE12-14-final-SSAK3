package com.react.global.websocket;

import com.react.global.exception.BusinessException;
import com.react.room.service.LectureParticipantService;
import com.react.room.service.ParticipantSessionPolicy;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * STOMP CONNECT에서 HTTP 세션과 CSRF 토큰을 검증한다.
 * SUBSCRIBE·SEND 시에도 인증된 연결인지 확인한다.
 *
 * 방별 권한 검사는 StompSubscribeInterceptor와
 * StompActorResolver에서 별도로 수행한다.
 */
@Component
public class StompSessionAuthInterceptor implements ChannelInterceptor {

    private static final String CONNECTED = "stompAuthenticated";
    private static final String CONNECTED_HOST = "stompConnectedHost";
    private static final String CONNECTED_ROLE = "stompConnectedRole";
    private static final String PARTICIPANT_EXPIRES_AT = "stompParticipantExpiresAt";

    private static final String HOST = "HOST";
    private static final String PARTICIPANT = "PARTICIPANT";
    private static final String AUTO = "AUTO";

    private final LectureParticipantService participantService;
    private final ParticipantSessionPolicy sessionPolicy;
    private final TaskScheduler expiryScheduler;

    public StompSessionAuthInterceptor(
            LectureParticipantService participantService,
            ParticipantSessionPolicy sessionPolicy,
            @Qualifier("stompExpiryScheduler") TaskScheduler expiryScheduler
    ) {
        this.participantService = participantService;
        this.sessionPolicy = sessionPolicy;
        this.expiryScheduler = expiryScheduler;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

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
        Map<String, Object> attributes = requireAttributes(accessor);
        HttpSession session = requireCurrentSession(attributes);
        HttpSessionWebSocketLinks links = requireActiveLinks(accessor, attributes);

        String expectedToken = (String) attributes.get(
                StompHandshakeInterceptor.CSRF_TOKEN
        );
        String submittedToken = accessor.getFirstNativeHeader("X-CSRF-TOKEN");

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

        String requestedRole = accessor.getFirstNativeHeader(
                StompActorResolver.CHAT_ROLE_HEADER
        );

        if (requestedRole != null
                && !HOST.equals(requestedRole)
                && !PARTICIPANT.equals(requestedRole)) {
            throw new AccessDeniedException(
                    "STOMP 연결 역할이 올바르지 않습니다."
            );
        }

        // 기존 채팅 클라이언트는 CONNECT에 역할을 보내지 않는다.
        // 따라서 역할을 지정하지 않으면 기존의 요청별 역할 선택을 유지한다.
        String connectedRole = requestedRole == null ? AUTO : requestedRole;

        Instant participantExpiry = PARTICIPANT.equals(connectedRole)
                || AUTO.equals(connectedRole)
                ? earliestParticipantExpiry(attributes)
                : null;

        Authentication authentication;

        if (HOST.equals(connectedRole)) {
            if (currentHost == null) {
                throw new AccessDeniedException(
                        "강연자 로그인 인증이 필요합니다."
                );
            }

            authentication = hostAuthentication(currentHost);

        } else if (PARTICIPANT.equals(connectedRole)) {
            if (participantExpiry == null) {
                throw new AccessDeniedException(
                        "유효한 청중 참여 인증이 없습니다."
                );
            }

            authentication = participantAuthentication(accessor);

        } else if (currentHost != null) {
            authentication = hostAuthentication(currentHost);

        } else if (participantExpiry != null) {
            authentication = participantAuthentication(accessor);

        } else {
            throw new AccessDeniedException(
                    "STOMP 연결 인증 정보가 없습니다."
            );
        }

        // 이후 STOMP 메시지에서 사용할 인증 주체
        accessor.setUser(authentication);

        if (currentHost != null) {
            attributes.put(CONNECTED_HOST, currentHost);
        }

        // 청중 인증을 사용할 수 있는 연결은 만료 시 소켓을 종료한다.
        // HOST/청중 혼합 연결도 포함해 기존 청중 구독이 남지 않도록 한다.
        if (participantExpiry != null) {
            attributes.put(PARTICIPANT_EXPIRES_AT, participantExpiry);

            try {
                links.expireAt(
                        accessor.getSessionId(),
                        participantExpiry,
                        expiryScheduler
                );
            } catch (RuntimeException exception) {
                links.closeSocket(accessor.getSessionId());

                throw new AccessDeniedException(
                        "청중 인증 만료 작업을 등록할 수 없습니다.",
                        exception
                );
            }
        }

        attributes.put(CONNECTED_ROLE, connectedRole);
        attributes.put(CONNECTED, Boolean.TRUE);
    }

    private void validateConnection(StompHeaderAccessor accessor) {
        Map<String, Object> attributes = requireAttributes(accessor);

        if (!Boolean.TRUE.equals(attributes.get(CONNECTED))) {
            throw new AccessDeniedException(
                    "STOMP CONNECT 인증이 필요합니다."
            );
        }

        HttpSession session = requireCurrentSession(attributes);
        HttpSessionWebSocketLinks links = requireActiveLinks(accessor, attributes);

        String connectedRole = (String) attributes.get(CONNECTED_ROLE);
        String requestedRole = accessor.getFirstNativeHeader(
                StompActorResolver.CHAT_ROLE_HEADER
        );

        // 명시적으로 선택한 연결 역할은 후속 메시지에서 변경할 수 없다.
        if (!AUTO.equals(connectedRole)
                && requestedRole != null
                && !requestedRole.equals(connectedRole)) {
            throw new AccessDeniedException(
                    "STOMP 연결 역할과 요청 역할이 다릅니다."
            );
        }

        Object storedExpiry = attributes.get(PARTICIPANT_EXPIRES_AT);

        if (storedExpiry instanceof Instant expiry
                && !Instant.now().isBefore(expiry)) {
            links.closeSocket(accessor.getSessionId());

            throw new AccessDeniedException(
                    "청중 인증이 만료되었습니다."
            );
        }

        String connectedHost = (String) attributes.get(CONNECTED_HOST);
        String currentHost = currentHost(session);
        String handshakeHost = (String) attributes.get(
                StompHandshakeInterceptor.HTTP_HOST_NAME
        );

        if (!Objects.equals(handshakeHost, currentHost)
                || !Objects.equals(connectedHost, currentHost)) {
            links.closeSocket(accessor.getSessionId());

            throw new AccessDeniedException(
                    "강연자 로그인 세션이 변경되었습니다."
            );
        }

        if (PARTICIPANT.equals(connectedRole)
                || (AUTO.equals(connectedRole) && connectedHost == null)) {
            if (earliestParticipantExpiry(attributes) == null) {
                links.closeSocket(accessor.getSessionId());

                throw new AccessDeniedException(
                        "청중 인증이 만료되었거나 무효화되었습니다."
                );
            }
        }
    }

    private Authentication hostAuthentication(String hostName) {
        return UsernamePasswordAuthenticationToken.authenticated(
                hostName,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_HOST"))
        );
    }

    private Authentication participantAuthentication(
            StompHeaderAccessor accessor
    ) {
        String socketId = accessor.getSessionId();

        if (socketId == null) {
            throw new AccessDeniedException(
                    "WebSocket 세션 ID가 없습니다."
            );
        }

        return UsernamePasswordAuthenticationToken.authenticated(
                "participant-ws:" + socketId,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_PARTICIPANT"))
        );
    }

    private HttpSessionWebSocketLinks requireActiveLinks(
            StompHeaderAccessor accessor,
            Map<String, Object> attributes
    ) {
        Object stored = attributes.get(
                HttpSessionWebSocketLinks.HANDSHAKE_ATTRIBUTE
        );

        if (!(stored instanceof HttpSessionWebSocketLinks links)
                || !links.isActive(accessor.getSessionId())) {
            throw new AccessDeniedException(
                    "WebSocket 연결이 종료되었거나 무효화되었습니다."
            );
        }

        return links;
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
            return StompHandshakeInterceptor.currentHostName(session);
        } catch (IllegalStateException exception) {
            throw new AccessDeniedException(
                    "HTTP 세션이 만료되었습니다.",
                    exception
            );
        }
    }

    /**
     * 유효한 참여 쿠키 중 가장 먼저 만료되는 시각을 반환한다.
     * 유효한 참여 쿠키가 없다면 null을 반환한다.
     *
     * 실제 DB 조회는 LectureParticipantService.me()의
     * readOnly 트랜잭션에서 수행한다.
     */
    private Instant earliestParticipantExpiry(
            Map<String, Object> attributes
    ) {
        Object stored = attributes.get(
                StompHandshakeInterceptor.PARTICIPANT_TOKENS
        );

        if (!(stored instanceof Map<?, ?> tokens)) {
            return null;
        }

        Instant earliest = null;
        Instant now = Instant.now();

        for (Map.Entry<?, ?> entry : tokens.entrySet()) {
            if (!(entry.getKey() instanceof Long roomId)
                    || !(entry.getValue() instanceof String token)) {
                continue;
            }

            try {
                Instant expiresAt = participantService
                        .me(roomId, token)
                        .joinedAt()
                        .plus(sessionPolicy.ttl());

                if (!expiresAt.isAfter(now)) {
                    continue;
                }

                if (earliest == null || expiresAt.isBefore(earliest)) {
                    earliest = expiresAt;
                }
            } catch (BusinessException ignored) {
                // 다른 방의 유효한 쿠키가 있을 수 있으므로 계속 확인
            }
        }

        return earliest;
    }

    private Map<String, Object> requireAttributes(
            StompHeaderAccessor accessor
    ) {
        Map<String, Object> attributes = accessor.getSessionAttributes();

        if (attributes == null) {
            throw new AccessDeniedException(
                    "WebSocket 세션 정보가 없습니다."
            );
        }

        return attributes;
    }
}
