package com.react.report.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ReportAlertRepository {
    private final JdbcClient jdbc;

    public record Alert(long reportId, String diagnosticCode, boolean retried, Instant failedAt) {}

    /** 매번 새 토큰으로 한 배치를 선점한다. 작업자가 중단되면 2분 후 다른 인스턴스가 이어받는다. */
    @Transactional
    public List<Alert> claim(UUID leaseToken) {
        String token = leaseToken.toString();
        int claimed = jdbc.sql("""
                UPDATE report_alert_outbox
                SET lease_token = ?, next_attempt_at = UTC_TIMESTAMP(6) + INTERVAL 2 MINUTE
                WHERE next_attempt_at <= UTC_TIMESTAMP(6)
                ORDER BY next_attempt_at, report_id, retry_used LIMIT 20
                """).param(token).update();
        if (claimed == 0) return List.of();
        return jdbc.sql("""
                SELECT report_id, diagnostic_code, retry_used, created_at FROM report_alert_outbox
                WHERE lease_token = ? ORDER BY created_at, report_id, retry_used
                """).param(token).query((rs, row) -> new Alert(rs.getLong("report_id"),
                rs.getString("diagnostic_code"), rs.getBoolean("retry_used"),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC))).list();
    }

    public void acknowledge(UUID leaseToken) {
        jdbc.sql("DELETE FROM report_alert_outbox WHERE lease_token = ?").param(leaseToken.toString()).update();
    }

    public void defer(UUID leaseToken) {
        jdbc.sql("""
                UPDATE report_alert_outbox SET lease_token = NULL, next_attempt_at = UTC_TIMESTAMP(6) + INTERVAL 5 MINUTE
                WHERE lease_token = ?
                """).param(leaseToken.toString()).update();
    }
}
