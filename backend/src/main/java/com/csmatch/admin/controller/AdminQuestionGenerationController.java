package com.csmatch.admin.controller;

import com.csmatch.admin.dto.QuestionGenerationDto;
import com.csmatch.admin.service.QuestionGenerationService;
import com.csmatch.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/questions/generate")
public class AdminQuestionGenerationController {
    private final QuestionGenerationService service;

    @PostMapping
    public ResponseEntity<ApiResponse<QuestionGenerationDto.Status>> start(
            @Valid @RequestBody QuestionGenerationDto.StartRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(service.start(request)));
    }

    @GetMapping("/status")
    public ApiResponse<QuestionGenerationDto.Status> status() {
        return ApiResponse.of(service.status());
    }
}
