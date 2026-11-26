package com.react.room.repository;

import com.react.report.dto.ReportReceipt;
import com.react.report.repository.ReportQueryRepository;
import com.react.room.dto.RoomHistoryStatuses;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class RoomHistoryRepository {
    private final JdbcClient jdbc;

    public long countEnded(long hostId) {
        return jdbc.sql("SELECT COUNT(*) FROM lecture_rooms WHERE host_user_id = ? AND status = 'ENDED'")
                .param(hostId).query(Long.class).single();
    }

    public List<Entry> page(long hostId, LocalDateTime beforeTime, Long beforeId, int limit) {
        return jdbc.sql("""
                SELECT r.id AS room_id, r.title, r.ended_at, p.status AS report_status,
                       COALESCE((%s), FALSE) AS report_unconfirmed
                FROM lecture_rooms r
                LEFT JOIN lecture_reports p ON p.lecture_room_id = r.id
                WHERE r.host_user_id = :host AND r.status = 'ENDED'
                  AND (:beforeId IS NULL OR r.ended_at < :beforeTime
                       OR (r.ended_at IS NULL AND :beforeTime IS NOT NULL)
                       OR (r.ended_at <=> :beforeTime AND r.id < :beforeId))
                ORDER BY r.ended_at DESC, r.id DESC
                LIMIT :limit
                """.formatted(ReportQueryRepository.UNCONFIRMED))
                .param("host", hostId).param("beforeTime", beforeTime)
                .param("beforeId", beforeId).param("limit", limit)
                .query(Entry.class).list();
    }

    public List<RoomHistoryStatuses.Room> statuses(long hostId, List<Long> roomIds) {
        return jdbc.sql("""
                SELECT r.id AS room_id, p.status AS report_status,
                       COALESCE((%s), FALSE) AS report_unconfirmed
                FROM lecture_rooms r
                LEFT JOIN lecture_reports p ON p.lecture_room_id = r.id
                WHERE r.host_user_id = :host AND r.status = 'ENDED' AND r.id IN (:ids)
                """.formatted(ReportQueryRepository.UNCONFIRMED))
                .param("host", hostId).param("ids", roomIds)
                .query(RoomHistoryStatuses.Room.class).list();
    }

    public record Entry(long roomId, String title, LocalDateTime endedAt, ReportReceipt.Status reportStatus,
                        boolean reportUnconfirmed) {}
}
