package com.react.poll.controller;

import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.global.response.ApiResponse;
import com.react.poll.dto.CreatePollRequest;
import com.react.poll.dto.MyPollResponseView;
import com.react.poll.dto.PollCloseView;
import com.react.poll.dto.PollListView;
import com.react.poll.dto.PollResponseView;
import com.react.poll.dto.PollResultView;
import com.react.poll.dto.PollView;
import com.react.poll.dto.RespondPollRequest;
import com.react.poll.dto.UnderstandingRoundsView;
import com.react.poll.entity.PollStatus;
import com.react.poll.service.PollService;
import com.react.room.service.ParticipantSessionResolver;
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

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/polls")
@RequiredArgsConstructor
public class PollController {
    private final PollService polls;
    private final ParticipantSessionResolver participantSessions;

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

    /** HOST는 강연방 소유자 권한으로 조회하고, 다른 방의 HOST는 유효한 청중 쿠키가 있으면 참여자 권한으로 조회한다. */
    @GetMapping
    public ApiResponse<PollListView> list(@PathVariable long roomId,
                                          @RequestParam(required = false) PollStatus status, Authentication authentication,
                                          HttpServletRequest servletRequest) {
        if (isHost(authentication)) {
            try {
                return ApiResponse.of(polls.list(roomId, authentication.getName(), status));
            } catch (BusinessException e) {
                if (e.getErrorCode() != ErrorCode.FORBIDDEN
                        || ParticipantSessionResolver.token(servletRequest, roomId) == null) {
                    throw e;
                }
            }
        }
        participantSessions.requireParticipantId(servletRequest, roomId);
        return ApiResponse.of(polls.listForParticipant(roomId, status));
    }

    /** 결과는 강연방 소유자(HOST)만 조회한다. 청중에게는 공개하지 않는다. */
    @GetMapping("/{pollId}/results")
    public ApiResponse<PollResultView> results(@PathVariable long roomId, @PathVariable long pollId,
                                               Authentication authentication) {
        return ApiResponse.of(polls.results(roomId, pollId, email(authentication)));
    }

    /** 마감된 이해도 체크를 시간순으로 비교한다. 강연방 소유자(HOST)만 조회한다. */
    @GetMapping("/understanding-rounds")
    public ApiResponse<UnderstandingRoundsView> understandingRounds(@PathVariable long roomId,
                                                                    @RequestParam(required = false) Integer minCount,
                                                                    @RequestParam(required = false) Double ratio,
                                                                    Authentication authentication) {
        return ApiResponse.of(polls.understandingRounds(roomId, email(authentication), minCount, ratio));
    }

    /** 참여자는 해당 방의 PARTICIPANT_SESSION 쿠키로만 확인한다. 쿠키가 없으면 401, 다른 방 것이면 403 이다. */
    @PutMapping("/{pollId}/responses/me")
    public ApiResponse<PollResponseView> respond(@PathVariable long roomId, @PathVariable long pollId,
                                                 @RequestBody RespondPollRequest request, HttpServletRequest servletRequest) {
        long participantId = participantSessions.requireParticipantId(servletRequest, roomId);
        if (request.optionId() == null) {
            throw new BusinessException(ErrorCode.INVALID_POLL_OPTION);
        }
        return ApiResponse.of(polls.respond(roomId, pollId, participantId, request.optionId()));
    }

    @GetMapping("/{pollId}/responses/me")
    public ApiResponse<MyPollResponseView> myResponse(@PathVariable long roomId, @PathVariable long pollId,
                                                      HttpServletRequest servletRequest) {
        long participantId = participantSessions.requireParticipantId(servletRequest, roomId);
        return ApiResponse.of(polls.myResponse(roomId, pollId, participantId));
    }

    private String email(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        if (!isHost(authentication)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return authentication.getName();
    }

    private static boolean isHost(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_HOST".equals(authority.getAuthority()));
    }
}
