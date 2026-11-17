package com.react.auth.dto;

import java.time.OffsetDateTime;

public record SignupResponse(
        Long userId,
        String email,
        String name,
        OffsetDateTime createdAt
) {
}