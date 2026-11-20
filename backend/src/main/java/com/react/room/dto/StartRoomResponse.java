package com.react.room.dto;

import java.time.Instant;

public record StartRoomResponse(
        Long roomId,
        String status,
        Instant startedAt
) {
}
