package com.react.stt.dto;

import java.time.Instant;

public record SttSessionView(long sttSessionId, long roomId, String status, Instant startedAt,
                             Instant endedAt, boolean canRecord) {
}
