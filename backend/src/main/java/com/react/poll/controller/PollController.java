package com.react.poll.controller;

import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.global.response.ApiResponse;
import com.react.poll.dto.CreatePollRequest;
import com.react.poll.dto.PollCloseView;
import com.react.poll.dto.PollListView;
import com.react.poll.dto.PollResponseView;
import com.react.poll.dto.PollView;
import com.react.poll.dto.RespondPollRequest;
import com.react.poll.entity.PollStatus;
import com.react.poll.service.PollService;
import com.react.room.service.LectureParticipantService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/polls")
@RequiredArgsConstructor
public class PollController {
    private static final String PARTICIPANT_COOKIE_PREFIX = "PARTICIPANT_SESSION_";

    private final PollService polls;
    private final LectureParticipantService participants;

    @PostMapping
    public ResponseEntity<ApiResponse<PollView>> create(@PathVariable long roomId,
            @RequestBody CreatePollRequest request, Authentication authentication) {
        return ResponseEntity.status(201).body(ApiResponse.of(polls.create(roomId, email(authentication), request)));
    }

    @PostMapping("/{pollId}/close")
    public ApiResponse<PollCloseView> close(@PathVariable long roomId, @PathVariable long pollId,
            Authentication authentication) {
        return ApiResponse.of(polls.close(roomId, pollId, email(authentication)));
    }

    // TODO(SCRUM-164): 청중 인증이 확정되면 청중도 질문·선택지·남은 시간을 조회할 수 있게 한다. 지금은 강연방 소유자만 조회한다.
    @GetMapping
    public ApiResponse<PollListView> list(@PathVariable long roomId,
            @RequestParam(required = false) PollStatus status, Authentication authentication) {
        return ApiResponse.of(polls.list(roomId, email(authentication), status));
    }

    /** 참여자는 해당 방의 PARTICIPANT_SESSION 쿠키로만 확인한다. 쿠키가 없으면 401, 다른 방 것이면 403 이다. */
    @PutMapping("/{pollId}/responses/me")
    public ApiResponse<PollResponseView> respond(@PathVariable long roomId, @PathVariable long pollId,
            @RequestBody RespondPollRequest request, HttpServletRequest servletRequest) {
        long participantId = participants.me(roomId, participantToken(servletRequest, roomId)).participantId();
        if (request.optionId() == null) {
            throw new BusinessException(ErrorCode.INVALID_POLL_OPTION);
        }
        return ApiResponse.of(polls.respond(roomId, pollId, participantId, request.optionId()));
    }

    private static String participantToken(HttpServletRequest request, long roomId) {
        if (request.getCookies() == null) {
            return null;
        }
        String name = PARTICIPANT_COOKIE_PREFIX + roomId;
        return Arrays.stream(request.getCookies())
                .filter(cookie -> name.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private String email(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        boolean isHost = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_HOST".equals(authority.getAuthority()));
        if (!isHost) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return authentication.getName();
    }
}
