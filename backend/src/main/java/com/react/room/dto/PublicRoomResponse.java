package com.react.room.dto;

public record PublicRoomResponse(
        Long roomId,
        String title,
        String description,
        String status,
        boolean canJoin
) {
}