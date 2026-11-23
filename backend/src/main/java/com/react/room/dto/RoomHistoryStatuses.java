package com.react.room.dto;

import com.react.report.dto.ReportReceipt;

import java.time.Instant;
import java.util.List;

public record RoomHistoryStatuses(List<Room> rooms, Instant serverTime) {
    public record Room(long roomId, ReportReceipt.Status reportStatus) {}
}
