package com.csmatch.auth.dto.response;

public record LoginResponse(
        String accessToken,
        long expiresIn
) {
}