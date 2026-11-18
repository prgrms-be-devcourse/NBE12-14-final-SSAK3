package com.react.feed.dto;

import java.time.Instant;

public record UnderstandingChangeView(
        long participantId,
        long roomId,
        UnderstandingStatus previousStatus,
        UnderstandingStatus currentStatus,
        boolean changed,
        Instant updatedAt
) { }
