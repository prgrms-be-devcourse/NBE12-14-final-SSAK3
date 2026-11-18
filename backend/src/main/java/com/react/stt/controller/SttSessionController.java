package com.react.stt.controller;

import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.global.response.ApiResponse;
import com.react.stt.dto.SttSessionView;
import com.react.stt.dto.SttGap;
import com.react.stt.dto.SttRecordsView;
import com.react.stt.dto.SttSegmentRequest;
import com.react.stt.dto.SttSegmentView;
import com.react.stt.service.SttSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/stt")
@RequiredArgsConstructor
public class SttSessionController {
    private final SttSessionService sessions;
    public record StartRequest(@NotNull UUID startKey) {}
    public record StopRequest(@NotNull SttSessionService.StopReason reason) {}

    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<SttSessionView>> start(@PathVariable long roomId,
            @Valid @RequestBody StartRequest request, Authentication authentication) {
        var result = sessions.start(roomId, email(authentication), request.startKey());
        return ResponseEntity.status(result.created() ? 201 : 200).body(ApiResponse.of(result.session()));
    }

    @PostMapping("/sessions/{sessionId}/stop")
    public ApiResponse<SttSessionView> stop(@PathVariable long roomId, @PathVariable long sessionId,
            @Valid @RequestBody StopRequest request, Authentication authentication) {
        return ApiResponse.of(sessions.stop(roomId, sessionId, email(authentication), request.reason()));
    }

    @PostMapping("/sessions/{sessionId}/segments")
    public ResponseEntity<ApiResponse<SttSegmentView>> saveSegment(@PathVariable long roomId,
            @PathVariable long sessionId, @Valid @RequestBody SttSegmentRequest request, Authentication authentication) {
        var result = sessions.saveSegment(roomId, sessionId, email(authentication), request);
        return ResponseEntity.status(result.created() ? 201 : 200).body(ApiResponse.of(result.segment()));
    }

    @PostMapping("/sessions/{sessionId}/gaps")
    public ResponseEntity<ApiResponse<SttGap.View>> openGap(@PathVariable long roomId, @PathVariable long sessionId,
            @Valid @RequestBody SttGap.OpenRequest request, Authentication authentication) {
        var result = sessions.openGap(roomId, sessionId, email(authentication), request);
        return ResponseEntity.status(result.created() ? 201 : 200).body(ApiResponse.of(result.gap()));
    }

    @PatchMapping("/sessions/{sessionId}/gaps/{gapId}/close")
    public ApiResponse<SttGap.ClosedView> closeGap(@PathVariable long roomId, @PathVariable long sessionId,
            @PathVariable long gapId, @Valid @RequestBody SttGap.CloseRequest request, Authentication authentication) {
        return ApiResponse.of(sessions.closeGap(roomId, sessionId, gapId, email(authentication), request));
    }

    @GetMapping("/segments")
    public ApiResponse<SttRecordsView> records(@PathVariable long roomId,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            Authentication authentication) {
        return ApiResponse.of(sessions.records(roomId, email(authentication), from, to));
    }

    private String email(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        return authentication.getName();
    }
}
