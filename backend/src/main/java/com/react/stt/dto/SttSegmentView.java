package com.react.stt.dto;

import java.time.Instant;

public record SttSegmentView(long segmentId, long sttSessionId, String segmentKey, String transcript,
                             Instant segmentStartAt, Instant segmentEndAt, Instant receivedAt) {
}
