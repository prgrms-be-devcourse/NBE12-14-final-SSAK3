package com.react.auth.dto;

public record LoginResponse(
        Long userId,
        String email,
        String name,
        boolean authenticated
) {
}
