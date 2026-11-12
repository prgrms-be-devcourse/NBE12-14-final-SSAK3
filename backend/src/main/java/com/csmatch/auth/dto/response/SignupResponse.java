package com.csmatch.auth.dto.response;

public record SignupResponse(
        Long userId,
        String nickname,
        int rating
) {
}