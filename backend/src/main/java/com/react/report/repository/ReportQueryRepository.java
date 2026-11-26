package com.react.report.repository;

import com.react.report.dto.ReportReceipt;
import com.react.report.dto.ReportStatusView;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ReportQueryRepository {
    private final JdbcClient jdbc;

    public Optional<ReportStatusView> findStatus(long roomId) {
        return jdbc.sql("""
                SELECT status, COALESCE((%s), FALSE) AS retry_available,
                       (status IN ('PENDING', 'PROCESSING') AND COALESCE(updated_at, created_at)
                         <= UTC_TIMESTAMP() + INTERVAL 9 HOUR - INTERVAL 120 SECOND) AS `delayed`,
                       CASE
                           WHEN status <> 'FAILED' THEN NULL
                           WHEN error_message IN ('LLM_REQUEST_FAILED', 'LLM_TEMPORARY_FAILURE') THEN 'AI_SERVICE'
                           WHEN error_message IN ('INVALID_REPORT_FORMAT', 'INVALID_REPORT_EVIDENCE') THEN 'AI_OUTPUT'
                           ELSE 'REPORT'
                       END AS failure_category
                FROM lecture_reports WHERE lecture_room_id = ?
                """.formatted(ReportRequestRepository.RETRY_AVAILABLE)).param(roomId)
                .query(ReportStatusView.class).optional();
    }

    public Optional<Detail> findDetail(long roomId) {
        return jdbc.sql("""
                SELECT id AS report_id, status, created_at,
                       CASE WHEN status = 'COMPLETED' THEN generated_at END AS generated_at,
                       CASE WHEN status = 'COMPLETED' THEN content END AS content
                FROM lecture_reports
                WHERE lecture_room_id = ?
                """).param(roomId).query(Detail.class).optional();
    }

    public record Detail(long reportId, ReportReceipt.Status status, LocalDateTime createdAt,
                         LocalDateTime generatedAt, String content) {}
}
