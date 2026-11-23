package com.react.report.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ReportGenerationRepository {
    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public record Candidate(long reportId, String email, boolean invalidTime, boolean ready) {}

    public Optional<Candidate> pending(long roomId) {
        return jdbc.sql("""
                SELECT report.id AS report_id, host.email,
                       (r.started_at IS NULL OR r.ended_at IS NULL OR r.ended_at < r.started_at
                        OR COALESCE(f.status = 'FAILED', FALSE)) AS invalid_time,
                       (COALESCE(f.status = 'COMPLETED', FALSE) AND NOT EXISTS (
                           SELECT 1 FROM polls p WHERE p.lecture_room_id = r.id
                           AND (p.status <> 'CLOSED' OR p.closed_at IS NULL OR p.closed_total_participants IS NULL)
                       )) AS ready
                FROM lecture_reports report
                JOIN lecture_rooms r ON r.id = report.lecture_room_id
                JOIN users host ON host.id = r.host_user_id
                LEFT JOIN ai_analysis_finalizations f ON f.lecture_room_id = r.id
                WHERE r.id = ? AND r.status = 'ENDED' AND report.status = 'PENDING'
                """).param(roomId).query(Candidate.class).optional();
    }

    public List<Long> pendingRoomIds() {
        return jdbc.sql("""
                SELECT lecture_room_id FROM lecture_reports WHERE status = 'PENDING' ORDER BY id
                """).query(Long.class).list();
    }

    public void failInvalidTime(long reportId) {
        jdbc.sql("""
                UPDATE lecture_reports SET status = 'FAILED', error_message = 'INVALID_LECTURE_TIME',
                       updated_at = UTC_TIMESTAMP() + INTERVAL 9 HOUR
                WHERE id = ? AND status = 'PENDING'
                """).param(reportId).update();
    }

    // MySQL releases this connection-scoped lock if the worker disconnects. A restarting
    // instance can distinguish an abandoned PROCESSING report from another live worker.
    private static final String LOCK_NAME = "SHA2(CONCAT(DATABASE(), ':lecture_reports:', %s), 256)";

    public void withClaim(long reportId, Runnable generate) {
        template.execute((ConnectionCallback<Void>) connection -> {
            var owner = JdbcClient.create(new SingleConnectionDataSource(connection, true));
            String name = LOCK_NAME.formatted("?");
            if (!owner.sql("SELECT GET_LOCK(" + name + ", 0)").param(reportId).query(Boolean.class).single()) {
                return null;
            }
            try {
                int claimed = owner.sql("""
                        UPDATE lecture_reports SET status = 'PROCESSING', updated_at = UTC_TIMESTAMP() + INTERVAL 9 HOUR
                        WHERE id = ? AND status = 'PENDING'
                        """).param(reportId).update();
                if (claimed == 1) {
                    generate.run();
                }
            } finally {
                owner.sql("SELECT RELEASE_LOCK(" + name + ")").param(reportId).query(Integer.class).single();
            }
            return null;
        });
    }

    public void failAbandonedJobs() {
        jdbc.sql("""
                UPDATE lecture_reports SET status = 'FAILED', error_message = 'GENERATION_INTERRUPTED',
                       updated_at = UTC_TIMESTAMP() + INTERVAL 9 HOUR
                WHERE status = 'PROCESSING' AND IS_FREE_LOCK(%s) = 1
                """.formatted(LOCK_NAME.formatted("id"))).update();
    }

    public void complete(long reportId, String content) {
        jdbc.sql("""
                UPDATE lecture_reports SET status = 'COMPLETED', content = CAST(? AS JSON),
                       generated_at = UTC_TIMESTAMP() + INTERVAL 9 HOUR, updated_at = UTC_TIMESTAMP() + INTERVAL 9 HOUR
                WHERE id = ? AND status = 'PROCESSING'
                """).params(content, reportId).update();
    }

    public void failGeneration(long reportId, String reason) {
        jdbc.sql("""
                UPDATE lecture_reports SET status = 'FAILED', error_message = ?, updated_at = UTC_TIMESTAMP() + INTERVAL 9 HOUR
                WHERE id = ? AND status = 'PROCESSING'
                """).params(reason, reportId).update();
    }
}
