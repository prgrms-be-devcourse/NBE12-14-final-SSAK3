package com.react.stt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class SttGap {
    private SttGap() {}

    public enum Reason { ERROR, MANUAL_STOP, PERMISSION_DENIED }

    public record OpenRequest(@NotNull UUID startKey, @NotBlank @Size(max = 100) String eventKey,
                              @NotNull Reason reason, @NotNull Instant startedAt) {}

    public record CloseRequest(@NotNull UUID startKey, @NotNull Instant endedAt) {}

    public record View(long gapId, long sttSessionId, String eventKey, Reason reason,
                       Instant startedAt, Instant endedAt) {}

    public record ClosedView(long gapId, Instant startedAt, Instant endedAt, Reason reason) {}
}
