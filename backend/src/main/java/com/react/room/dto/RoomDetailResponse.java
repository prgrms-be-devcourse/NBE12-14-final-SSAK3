package com.react.room.dto;

import java.time.Instant;

public record RoomDetailResponse(
        Long roomId,
        String title,
        String description,
        String status,
        String joinCode,
        Instant createdAt,
        Instant startedAt,
        Instant endedAt
) {
}