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
        return jdbc.sql("SELECT status FROM lecture_reports WHERE lecture_room_id = ?")
                .param(roomId).query(ReportStatusView.class).optional();
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
