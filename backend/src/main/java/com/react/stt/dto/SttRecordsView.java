package com.react.stt.dto;

import java.time.Instant;
import java.util.List;

public record SttRecordsView(long roomId, List<Segment> segments, List<Gap> gaps) {
    public record Segment(long segmentId, long sessionId, String transcript,
                          Instant segmentStartAt, Instant segmentEndAt, Instant receivedAt) {}

    public record Gap(long gapId, long sessionId, SttGap.Reason reason, Instant startedAt, Instant endedAt) {}
}
