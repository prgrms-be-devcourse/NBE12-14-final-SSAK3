package com.react.room.dto;

import com.react.report.dto.ReportReceipt;

import java.time.Instant;
import java.util.List;

public record RoomHistoryPage(List<Room> rooms, String nextCursor, long totalCount, Instant serverTime) {
    public record Room(long roomId, String title, Instant endedAt, ReportReceipt.Status reportStatus) {}

    public record Count(long totalCount) {}
}
