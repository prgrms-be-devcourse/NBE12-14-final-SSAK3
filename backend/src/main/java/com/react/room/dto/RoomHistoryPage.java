package com.react.room.dto;

import com.react.report.dto.ReportReceipt;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

public record RoomHistoryPage(List<Room> rooms, String nextCursor, long totalCount, Instant serverTime) {
    public record Room(long roomId, String title, Instant endedAt, ReportReceipt.Status reportStatus,
                       @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean reportUnconfirmed) {}

    public record Count(long totalCount) {}
}
