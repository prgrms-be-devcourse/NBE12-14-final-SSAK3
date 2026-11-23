package com.react.room.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 요청의 방별 PARTICIPANT_SESSION 쿠키로 참여자를 확인한다.
 * 쿠키가 없거나 만료·위조면 401, 다른 방의 토큰이면 403 이다. participantId 는 항상 서버가 확인한 값이다.
 */
@Component
@RequiredArgsConstructor
public class ParticipantSessionResolver {
    private static final String COOKIE_PREFIX = "PARTICIPANT_SESSION_";

    private final LectureParticipantService participants;

    public long requireParticipantId(HttpServletRequest request, long roomId) {
        return participants.me(roomId, token(request, roomId)).participantId();
    }

    public static String token(HttpServletRequest request, long roomId) {
        if (request.getCookies() == null) {
            return null;
        }
        String name = COOKIE_PREFIX + roomId;
        return Arrays.stream(request.getCookies())
                .filter(cookie -> name.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
