package com.react.room.controller;

import com.react.global.response.ApiResponse;
import com.react.room.dto.JoinParticipantRequest;
import com.react.room.dto.JoinParticipantResponse;
import com.react.room.dto.ParticipantMeResponse;
import com.react.room.service.LectureParticipantService;
import com.react.room.service.ParticipantSessionPolicy;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class LectureParticipantController {

    private static final String COOKIE_PREFIX = "PARTICIPANT_SESSION_";

    private final LectureParticipantService participantService;
    private final ParticipantSessionPolicy sessionPolicy;

    @PostMapping("/api/v1/public/rooms/{joinCode}/participants")
    public ResponseEntity<ApiResponse<JoinParticipantResponse>> join(
            @PathVariable String joinCode,
            @RequestBody JoinParticipantRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false)
            String idempotencyKey,
            HttpServletRequest servletRequest
    ) {
        LectureParticipantService.JoinResult result = participantService.join(
                joinCode,
                request.nickname(),
                idempotencyKey,
                tokensByRoom(servletRequest)
        );

        ResponseEntity.BodyBuilder builder = ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore());

        // 최초 입장과 같은 입장 시도의 재전송에는 동일한 쿠키를 발급한다.
        if (result.rawToken() != null) {
            Duration remaining = Duration.between(
                    Instant.now(),
                    result.response().joinedAt().plus(sessionPolicy.ttl())
            );

            ResponseCookie cookie = ResponseCookie
                    .from(cookieName(result.response().roomId()), result.rawToken())
                    .httpOnly(true)
                    .secure(sessionPolicy.secureCookie())
                    .sameSite("Lax")
                    .path("/")
                    .maxAge(remaining.isNegative() ? Duration.ZERO : remaining)
                    .build();

            builder.header(HttpHeaders.SET_COOKIE, cookie.toString());
        }

        return builder.body(ApiResponse.of(result.response()));
    }

    @GetMapping("/api/v1/rooms/{roomId}/participants/me")
    public ResponseEntity<ApiResponse<ParticipantMeResponse>> me(
            @PathVariable Long roomId,
            HttpServletRequest servletRequest
    ) {
        String token = tokensByRoom(servletRequest).get(roomId);
        ParticipantMeResponse response = participantService.me(roomId, token);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(response));
    }

    private static String cookieName(Long roomId) {
        return COOKIE_PREFIX + roomId;
    }

    private static Map<Long, String> tokensByRoom(HttpServletRequest request) {
        Map<Long, String> tokens = new HashMap<>();
        Cookie[] cookies = request.getCookies();

        if (cookies == null) {
            return tokens;
        }

        for (Cookie cookie : cookies) {
            String name = cookie.getName();
            if (!name.startsWith(COOKIE_PREFIX)) {
                continue;
            }

            String suffix = name.substring(COOKIE_PREFIX.length());

            // 숫자로 된 방 ID만 허용한다.
            if (!suffix.matches("[1-9][0-9]{0,18}")) {
                continue;
            }

            try {
                Long roomId = Long.valueOf(suffix);
                tokens.putIfAbsent(roomId, cookie.getValue());
            } catch (NumberFormatException ignored) {
                // Long 범위를 초과한 임의 쿠키는 무시한다.
            }
        }

        return tokens;
    }
}
