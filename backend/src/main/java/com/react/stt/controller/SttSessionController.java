package com.react.stt.controller;

import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.global.response.ApiResponse;
import com.react.stt.dto.SttSessionView;
import com.react.stt.service.SttSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/stt/sessions")
@RequiredArgsConstructor
public class SttSessionController {
    private final SttSessionService sessions;
    public record StartRequest(@NotNull UUID startKey) {}
    public record StopRequest(@NotNull SttSessionService.StopReason reason) {}

    @PostMapping
    public ResponseEntity<ApiResponse<SttSessionView>> start(@PathVariable long roomId,
            @Valid @RequestBody StartRequest request, Authentication authentication) {
        var result = sessions.start(roomId, email(authentication), request.startKey());
        return ResponseEntity.status(result.created() ? 201 : 200).body(ApiResponse.of(result.session()));
    }

    @PostMapping("/{sessionId}/stop")
    public ApiResponse<SttSessionView> stop(@PathVariable long roomId, @PathVariable long sessionId,
            @Valid @RequestBody StopRequest request, Authentication authentication) {
        return ApiResponse.of(sessions.stop(roomId, sessionId, email(authentication), request.reason()));
    }

    private String email(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        }
        return authentication.getName();
    }
}
