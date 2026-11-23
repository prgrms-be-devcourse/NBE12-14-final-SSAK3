package com.react.global.websocket;

import com.react.auth.entity.User;
import com.react.auth.repository.UserRepository;
import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.room.repository.LectureRoomRepository;
import com.react.room.service.LectureParticipantService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.util.Map;
import java.util.Optional;

/**
 * 한 강연방에 대한 구독·전송과 채팅 내역 조회에서 강연자 로그인 및 청중 입장 증명을 검증한다.
 * STOMP는 존재하지 않는 방도 증명에 따라 401·403으로 거절한다. 로그인한 강연자의 내역 조회는 404를 반환한다.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StompActorResolver {
    public static final String CHAT_ROLE_HEADER = "X-Chat-Role";
    private static final String HOST_ROLE = "ROLE_HOST";

    private final UserRepository users;
    private final LectureRoomRepository rooms;
    private final LectureParticipantService participants;

    public StompActor resolve(long roomId, Principal principal, Map<String, Object> sessionAttributes) {
        return resolveActor(roomId, principal, participantToken(sessionAttributes, roomId), null, false);
    }

    public StompActor resolveChat(long roomId, SimpMessageHeaderAccessor headers) {
        return resolveActor(roomId, headers.getUser(), participantToken(headers.getSessionAttributes(), roomId),
                headers.getFirstNativeHeader(CHAT_ROLE_HEADER), false);
    }

    public StompActor resolveHistory(long roomId, Principal principal, String token, String role) {
        return resolveActor(roomId, principal, token, role, true);
    }

    private StompActor resolveActor(long roomId, Principal principal, String token, String role, boolean history) {
        if (role != null && !"HOST".equals(role) && !"PARTICIPANT".equals(role)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        boolean forbidden = false;

        Optional<User> host = "PARTICIPANT".equals(role) ? Optional.empty() : loggedInHost(principal);
        if (host.isPresent()) {
            var room = rooms.findById(roomId);
            if (history && room.isEmpty()) {
                throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
            }
            if (room.isPresent() && room.get().getHost().getId().equals(host.get().getId())) {
                return StompActor.host(host.get().getId());
            }
            forbidden = true;
        }

        if (token != null && !"HOST".equals(role)) {
            try {
                return StompActor.participant(participants.me(roomId, token).participantId());
            } catch (BusinessException e) {
                forbidden |= e.getErrorCode() == ErrorCode.FORBIDDEN;
            }
        }

        throw new BusinessException(forbidden ? ErrorCode.FORBIDDEN : ErrorCode.UNAUTHENTICATED);
    }

    private Optional<User> loggedInHost(Principal principal) {
        if (!(principal instanceof Authentication authentication)
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        boolean isHost = authentication.getAuthorities().stream()
                .anyMatch(authority -> HOST_ROLE.equals(authority.getAuthority()));
        return isHost ? users.findByEmail(authentication.getName()) : Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static String participantToken(Map<String, Object> sessionAttributes, long roomId) {
        Object tokens = sessionAttributes == null
                ? null
                : sessionAttributes.get(StompHandshakeInterceptor.PARTICIPANT_TOKENS);
        return tokens instanceof Map<?, ?> map ? ((Map<Long, String>) map).get(roomId) : null;
    }
}
