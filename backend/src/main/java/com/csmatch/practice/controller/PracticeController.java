package com.csmatch.practice.controller;

import com.csmatch.global.exception.BusinessException;
import com.csmatch.global.exception.ErrorCode;
import com.csmatch.global.response.ApiResponse;
import com.csmatch.practice.dto.PracticeDto;
import com.csmatch.practice.service.PracticeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/practice")
public class PracticeController {
    private final PracticeService service;

    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<PracticeDto.Session>> start(@AuthenticationPrincipal Long userId,
            @Valid @RequestBody PracticeDto.StartRequest request) {
        var result = service.start(userId, request.categoryId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NO_MORE_QUESTIONS));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.of(result.session()));
    }

    @GetMapping("/sessions/current")
    public ApiResponse<PracticeDto.Session> current(@AuthenticationPrincipal Long userId) {
        return ApiResponse.of(service.currentSession(userId).orElse(null));
    }

    @GetMapping("/sessions/{sessionId}/question")
    public ApiResponse<PracticeDto.NextQuestion> next(@AuthenticationPrincipal Long userId, @PathVariable Long sessionId) {
        return ApiResponse.of(service.next(userId, sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NO_MORE_QUESTIONS)));
    }

    @PostMapping("/sessions/{sessionId}/answers")
    public ApiResponse<PracticeDto.AnswerResult> answer(@AuthenticationPrincipal Long userId, @PathVariable Long sessionId,
            @Valid @RequestBody PracticeDto.AnswerRequest request) {
        var result = service.submit(userId, sessionId, request);
        if (result.error() != null) {
            throw new BusinessException(result.error());
        }
        return ApiResponse.of(result.result());
    }

    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<PracticeDto.Session> detail(@AuthenticationPrincipal Long userId, @PathVariable Long sessionId) {
        return ApiResponse.of(service.detail(userId, sessionId));
    }

    @GetMapping("/sessions/{sessionId}/questions")
    public ApiResponse<List<PracticeDto.HistoryItem>> history(@AuthenticationPrincipal Long userId,
            @PathVariable Long sessionId, @RequestParam(defaultValue = "false") boolean wrongOnly) {
        return ApiResponse.of(service.history(userId, sessionId, wrongOnly));
    }

    @PostMapping("/sessions/{sessionId}/end")
    public ApiResponse<PracticeDto.Session> end(@AuthenticationPrincipal Long userId, @PathVariable Long sessionId) {
        return ApiResponse.of(service.abandon(userId, sessionId));
    }

    @GetMapping("/sessions")
    public ApiResponse<PracticeDto.PageResult<PracticeDto.Session>> list(@AuthenticationPrincipal Long userId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.of(service.list(userId, page, size));
    }

    @GetMapping("/progress")
    public ApiResponse<PracticeDto.Progress> progress(@AuthenticationPrincipal Long userId,
            @RequestParam(required = false) Integer categoryId) {
        return ApiResponse.of(service.getProgress(userId, categoryId));
    }

    @PostMapping("/restart")
    public ApiResponse<PracticeDto.Progress> restart(@AuthenticationPrincipal Long userId) {
        return ApiResponse.of(service.restart(userId));
    }

    @GetMapping("/categories")
    public ApiResponse<List<PracticeDto.Category>> categories() {
        return ApiResponse.of(service.categories());
    }
}
