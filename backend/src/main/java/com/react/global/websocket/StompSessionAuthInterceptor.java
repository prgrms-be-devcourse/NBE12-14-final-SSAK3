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

@Component
public class StompSessionAuthInterceptor implements ChannelInterceptor {

    private static final String CONNECTED_HOST =
            "stompConnectedHost";

    private static final String CONNECTED =
            "stompAuthenticated";

    private static final String PARTICIPANT_EXPIRES_AT =
            "stompParticipantExpiresAt";

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

        HttpSessionWebSocketLinks links =
                requireActiveLinks(accessor, attributes);

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

        // 유효한 청중 쿠키 중 가장 빠른 만료 시각 확인
        Instant earliestExpiry =
                earliestParticipantExpiry(attributes);

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

        } else if (earliestExpiry != null) {
            String socketId = accessor.getSessionId();

            authentication =
                    UsernamePasswordAuthenticationToken.authenticated(
                            "participant-ws:" + socketId,
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

        // 유효한 청중 쿠키가 있다면 가장 빠른 만료 시각에
        // 기존 구독을 포함한 WebSocket 연결 전체를 종료한다.
        if (earliestExpiry != null) {
            attributes.put(
                    PARTICIPANT_EXPIRES_AT,
                    earliestExpiry
            );

            try {
                links.expireAt(
                        accessor.getSessionId(),
                        earliestExpiry,
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

        attributes.put(CONNECTED, Boolean.TRUE);
    }

    private void validateConnection(
            StompHeaderAccessor accessor
    ) {
        Map<String, Object> attributes =
                requireAttributes(accessor);

        if (!Boolean.TRUE.equals(attributes.get(CONNECTED))) {
            throw new AccessDeniedException(
                    "STOMP CONNECT 인증이 필요합니다."
            );
        }

        HttpSession session = requireCurrentSession(attributes);

        HttpSessionWebSocketLinks links =
                requireActiveLinks(accessor, attributes);

        // 청중 인증 만료 후 스케줄러의 종료 작업이 실행되기 전에도
        // 새로운 구독이나 전송을 허용하지 않는다.
        Object expiresAt = attributes.get(PARTICIPANT_EXPIRES_AT);

        if (expiresAt instanceof Instant expiry
                && !Instant.now().isBefore(expiry)) {

            links.closeSocket(accessor.getSessionId());

            throw new AccessDeniedException(
                    "청중 인증이 만료되었습니다."
            );
        }

        String connectedHost =
                (String) attributes.get(CONNECTED_HOST);

        String currentHost = currentHost(session);

        if (connectedHost != null) {
            if (!connectedHost.equals(currentHost)) {
                links.closeSocket(accessor.getSessionId());

                throw new AccessDeniedException(
                        "강연자 로그인 세션이 변경되었습니다."
                );
            }

            return;
        }

        // 청중으로 연결했다면 후속 요청에서도
        // 적어도 하나 이상의 유효한 참여 인증이 있어야 한다.
        if (currentHost != null
                || earliestParticipantExpiry(attributes) == null) {

            links.closeSocket(accessor.getSessionId());

            throw new AccessDeniedException(
                    "청중 인증이 만료되었거나 세션이 변경되었습니다."
            );
        }
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

    /**
     * 유효한 참여 쿠키 중 가장 먼저 만료되는 시각을 반환한다.
     * 유효한 참여 쿠키가 없다면 null을 반환한다.
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
