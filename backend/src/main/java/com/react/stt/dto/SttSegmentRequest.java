package com.react.stt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record SttSegmentRequest(@NotNull UUID startKey,
                                @NotBlank @Size(max = 100) String segmentKey,
                                @NotBlank String transcript,
                                @NotNull Instant segmentStartAt,
                                @NotNull Instant segmentEndAt) {
}
