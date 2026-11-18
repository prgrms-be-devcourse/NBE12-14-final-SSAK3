package com.react.room.dto;

import java.time.Instant;

public record CreateRoomResponse(
        Long roomId,
        String title,
        String description,
        String status,
        String joinCode,
        Instant createdAt
) {
}