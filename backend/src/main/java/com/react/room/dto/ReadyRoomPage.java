package com.react.room.dto;

import java.time.Instant;
import java.util.List;

public record ReadyRoomPage(List<Room> rooms, String nextCursor) {
    public record Room(long roomId, String title, Instant createdAt) {
    }
}
