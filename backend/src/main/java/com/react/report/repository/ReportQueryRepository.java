package com.react.report.repository;

import com.react.report.dto.ReportReceipt;
import com.react.report.dto.ReportStatusView;
import com.react.report.dto.ReportStatusView.FailureCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ReportQueryRepository {
    // A failed retry can still be followed by a valid result from the original submission.
    public static final String UNCONFIRMED = """
            p.status = 'FAILED' AND (p.error_message = 'REPORT_DISPATCH_UNKNOWN' OR EXISTS (
                SELECT 1 FROM report_generation_attempts a
                WHERE a.report_id = p.id AND a.state IN ('UNKNOWN', 'REMOTE')
            ))
            """;
    static final String VISIBLE_FAILURE_REASON = "CASE WHEN " + UNCONFIRMED
            + " THEN 'REPORT_DISPATCH_UNKNOWN' ELSE p.error_message END";
    private final JdbcClient jdbc;

    public Optional<ReportStatusView> findStatus(long roomId) {
        return jdbc.sql("""
                SELECT status, COALESCE((%s), FALSE) AS retry_available,
                       (status IN ('PENDING', 'PROCESSING') AND COALESCE(updated_at, created_at)
                         <= UTC_TIMESTAMP() + INTERVAL 9 HOUR - INTERVAL 120 SECOND) AS `delayed`,
                       %s AS error_message
                FROM lecture_reports p WHERE lecture_room_id = ?
                """.formatted(ReportRequestRepository.RETRY_AVAILABLE, VISIBLE_FAILURE_REASON)).param(roomId)
                .query((rs, row) -> {
                    var status = ReportReceipt.Status.valueOf(rs.getString("status"));
                    return new ReportStatusView(status, rs.getBoolean("retry_available"), rs.getBoolean("delayed"),
                            FailureCategory.from(status, rs.getString("error_message")));
                }).optional();
    }

    public Optional<Detail> findDetail(long roomId) {
        return jdbc.sql("""
                SELECT id AS report_id, status, created_at,
                       %s AS error_message,
                       CASE WHEN status = 'COMPLETED' THEN generated_at END AS generated_at,
                       CASE WHEN status = 'COMPLETED' THEN content END AS content
                FROM lecture_reports p
                WHERE lecture_room_id = ?
                """.formatted(VISIBLE_FAILURE_REASON)).param(roomId).query(Detail.class).optional();
    }

    public record Detail(long reportId, ReportReceipt.Status status, LocalDateTime createdAt, String errorMessage,
                         LocalDateTime generatedAt, String content) {}
}
