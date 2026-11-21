package com.react.global.websocket;

import com.react.auth.entity.User;
import com.react.auth.repository.UserRepository;
import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.room.repository.LectureRoomRepository;
import com.react.room.service.LectureParticipantService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.util.Map;
import java.util.Optional;

/**
 * 한 강연방에 대해 STOMP 요청자가 강연자인지 청중인지 판별한다. 구독과 전송이 같은 규칙을 쓰도록 한 곳에 둔다.
 * 실패는 REST 와 같다: 이 방에 낼 증명이 없으면 401, 증명은 냈지만 이 방에 맞지 않으면 403 이다.
 * 방이 없는 경우도 같은 규칙으로 거절해 방 번호 탐색에 단서를 주지 않는다.
 */
@Component
@RequiredArgsConstructor
public class StompActorResolver {
    private static final String HOST_ROLE = "ROLE_HOST";

    private final UserRepository users;
    private final LectureRoomRepository rooms;
    private final LectureParticipantService participants;

    @Transactional(readOnly = true)
    public StompActor resolve(long roomId, Principal principal, Map<String, Object> sessionAttributes) {
        boolean forbidden = false;

        Optional<User> host = loggedInHost(principal);
        if (host.isPresent()) {
            if (ownsRoom(roomId, host.get())) {
                return StompActor.host(host.get().getId());
            }
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        String token = participantToken(sessionAttributes, roomId);
        if (token != null) {
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

    private boolean ownsRoom(long roomId, User host) {
        return rooms.findById(roomId)
                .map(room -> room.getHost().getId().equals(host.getId()))
                .orElse(false);
    }

    @SuppressWarnings("unchecked")
    private static String participantToken(Map<String, Object> sessionAttributes, long roomId) {
        Object tokens = sessionAttributes == null
                ? null
                : sessionAttributes.get(StompHandshakeInterceptor.PARTICIPANT_TOKENS);
        return tokens instanceof Map<?, ?> map ? ((Map<Long, String>) map).get(roomId) : null;
    }
}
