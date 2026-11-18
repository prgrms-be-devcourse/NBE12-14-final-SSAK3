package com.react.auth.dto;

public record MeResponse(
        Long userId,
        String email,
        String name
) {
}