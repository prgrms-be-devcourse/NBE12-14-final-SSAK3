package com.csmatch.review.controller;

import com.csmatch.global.response.ApiResponse;
import com.csmatch.practice.dto.PracticeDto;
import com.csmatch.review.dto.ReviewDto;
import com.csmatch.review.service.ReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/review-questions")
public class ReviewController {
    private final ReviewService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReviewDto.Saved> save(@AuthenticationPrincipal Long userId,
            @Valid @RequestBody ReviewDto.SaveRequest request) {
        return ApiResponse.of(service.save(userId, request));
    }

    @GetMapping
    public ApiResponse<PracticeDto.PageResult<ReviewDto.Item>> list(@AuthenticationPrincipal Long userId,
            @RequestParam(required = false) Integer categoryId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.of(service.list(userId, categoryId, page, size));
    }
}
