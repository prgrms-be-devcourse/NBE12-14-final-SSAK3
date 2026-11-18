package com.react.feed.dto;

import java.time.Instant;

/** 미응답이면 status·updatedAt 이 null 이다. 미선택은 모르겠음으로 간주하지 않는다. */
public record MyUnderstandingView(
        long participantId,
        UnderstandingStatus status,
        Instant updatedAt
) { }
