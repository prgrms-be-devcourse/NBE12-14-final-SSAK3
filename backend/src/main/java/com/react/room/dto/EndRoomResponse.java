package com.react.room.dto;

import java.time.Instant;

public record EndRoomResponse(
        Long roomId,
        String status,
        Instant endedAt,
        boolean reportReady,
        boolean pendingFinalization
) {
}
