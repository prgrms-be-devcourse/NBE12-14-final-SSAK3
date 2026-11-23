package com.react.report.repository;

import com.react.report.dto.ReportReceipt;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ReportRequestRepository {
    private final JdbcClient jdbc;

    public Optional<ReportReceipt> findByRoom(long roomId) {
        return jdbc.sql("SELECT id, status, created_at FROM lecture_reports WHERE lecture_room_id = ?")
                .param(roomId)
                .query((rs, row) -> new ReportReceipt(rs.getLong("id"), roomId,
                        ReportReceipt.Status.valueOf(rs.getString("status")),
                        rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.ofHours(9))))
                .optional();
    }

    public void insertPending(long roomId) {
        jdbc.sql("""
                INSERT INTO lecture_reports(lecture_room_id, status, created_at, updated_at)
                VALUES (?, 'PENDING', UTC_TIMESTAMP() + INTERVAL 9 HOUR, UTC_TIMESTAMP() + INTERVAL 9 HOUR)
                """).param(roomId).update();
    }
}
