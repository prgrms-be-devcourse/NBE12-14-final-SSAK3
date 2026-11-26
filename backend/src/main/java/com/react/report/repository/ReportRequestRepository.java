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
    static final String RETRY_AVAILABLE = "status = 'FAILED' AND error_message = 'LLM_TEMPORARY_FAILURE' AND retry_used = FALSE";
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

    public boolean retryTemporaryFailure(long roomId) {
        return jdbc.sql("""
                UPDATE lecture_reports
                SET status = 'PENDING', error_message = NULL, retry_used = TRUE, updated_at = UTC_TIMESTAMP() + INTERVAL 9 HOUR
                WHERE lecture_room_id = ? AND %s
                """.formatted(RETRY_AVAILABLE)).param(roomId).update() == 1;
    }
}
