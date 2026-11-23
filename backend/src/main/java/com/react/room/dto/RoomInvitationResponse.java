package com.react.room.dto;

public record RoomInvitationResponse(
        Long roomId,
        String joinCode,
        String joinUrl,
        String qrText
) {
}