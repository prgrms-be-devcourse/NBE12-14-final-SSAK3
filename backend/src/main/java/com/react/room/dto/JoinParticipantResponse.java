package com.react.room.dto;

import java.time.Instant;

public record JoinParticipantResponse(
        Long participantId,
        Long roomId,
        String nickname,
        String roomStatus,
        Instant joinedAt
) {
}
