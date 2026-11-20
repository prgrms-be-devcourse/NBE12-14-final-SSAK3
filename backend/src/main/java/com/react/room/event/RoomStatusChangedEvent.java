package com.react.room.event;

import com.react.room.entity.LectureRoomStatus;

import java.time.Instant;

public record RoomStatusChangedEvent(
        Long roomId,
        LectureRoomStatus status,
        Instant changedAt
) {
}
