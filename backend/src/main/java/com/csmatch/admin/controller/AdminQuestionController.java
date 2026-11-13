package com.csmatch.admin.controller;

import com.csmatch.admin.dto.AdminQuestionDto;
import com.csmatch.admin.service.AdminQuestionService;
import com.csmatch.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/questions")
public class AdminQuestionController {
    private final AdminQuestionService service;

    @PostMapping
    public ResponseEntity<ApiResponse<AdminQuestionDto.CreateResponse>> create(
            @AuthenticationPrincipal Long adminId,
            @Valid @RequestBody AdminQuestionDto.CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(service.create(adminId, request)));
    }
}
