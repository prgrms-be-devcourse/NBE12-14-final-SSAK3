package com.react.room.dto;

import java.time.Instant;

public record ParticipantMeResponse(
        Long participantId,
        Long roomId,
        String nickname,
        Instant joinedAt
) {
}
