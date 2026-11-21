package com.react.room.dto;

import java.time.Instant;

public record LiveRoomResponse(Long roomId, String title, Instant startedAt) {
}
