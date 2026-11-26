package com.react.report.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ReportAttemptRepository {
    private final JdbcClient jdbc;

    public record Attempt(UUID attemptKey, long reportId, boolean retryUsed, String responseId, String inputJson) {}

    private static final String ATTEMPT = """
            SELECT attempt_key, report_id, retry_used, response_id, input_json FROM report_generation_attempts
            """;
    // MySQL's getObject(UUID.class) interprets the CHAR(36) value as binary UUID bytes.
    private static final RowMapper<Attempt> ATTEMPT_ROW = (row, index) -> new Attempt(
            UUID.fromString(row.getString("attempt_key")), row.getLong("report_id"), row.getBoolean("retry_used"),
            row.getString("response_id"), row.getString("input_json"));
    private static final String NOW = "UTC_TIMESTAMP(6)";

    public boolean hasAttempt(long reportId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM report_generation_attempts WHERE report_id=?)")
                .param(reportId).query(Boolean.class).single();
    }

    /** Snapshot and slot both commit before the POST. A busy queue leaves the report PENDING. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<Attempt> reserve(long reportId, String inputJson) {
        var ordinal = jdbc.sql("SELECT retry_used FROM lecture_reports WHERE id=? AND status='PENDING' FOR UPDATE")
                .param(reportId).query(Boolean.class).optional();
        if (ordinal.isEmpty()) return Optional.empty();
        var slot = jdbc.sql("""
                SELECT slot_id FROM report_generation_slots FORCE INDEX (PRIMARY)
                WHERE attempt_key IS NULL ORDER BY slot_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """).query(Integer.class).optional();
        if (slot.isEmpty()) return Optional.empty();
        UUID key = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO report_generation_attempts(attempt_key,report_id,retry_used,state,input_json,next_action_at)
                VALUES (?,?,?,'SUBMITTING',CAST(? AS JSON),UTC_TIMESTAMP(6))
                """).params(key.toString(), reportId, ordinal.get(), inputJson).update();
        jdbc.sql("UPDATE report_generation_slots SET attempt_key=? WHERE slot_id=?")
                .params(key.toString(), slot.get()).update();
        jdbc.sql("""
                UPDATE lecture_reports SET status='PROCESSING',updated_at=UTC_TIMESTAMP()+INTERVAL 9 HOUR WHERE id=?
                """).param(reportId).update();
        return Optional.of(new Attempt(key, reportId, ordinal.get(), null, inputJson));
    }

    public Optional<Attempt> findByResponseId(String responseId) {
        return jdbc.sql(ATTEMPT + " WHERE response_id=? AND state='REMOTE'")
                .param(responseId).query(ATTEMPT_ROW).optional();
    }

    /** The provider metadata must be verified before attaching an ID discovered via webhook. */
    @Transactional
    public Optional<Attempt> attach(UUID attemptKey, String responseId) {
        if (!lockReport(attemptKey)) return Optional.empty();
        // The retention deadline applies even if the periodic cleanup has not run yet.
        expireIfDue(attemptKey);
        try {
            jdbc.sql("""
                    UPDATE report_generation_attempts SET state=IF(state='EXPIRED','DELETE_PENDING','REMOTE'),
                           response_id=?,next_action_at=UTC_TIMESTAMP(6)
                    WHERE attempt_key=? AND response_id IS NULL AND state IN ('SUBMITTING','UNKNOWN','EXPIRED')
                    """).params(responseId, attemptKey.toString()).update();
        } catch (DuplicateKeyException differentOwner) {
            return Optional.empty();
        }
        var attached = jdbc.sql(ATTEMPT + " WHERE attempt_key=? AND response_id=? AND state='REMOTE' FOR UPDATE")
                .params(attemptKey.toString(), responseId).query(ATTEMPT_ROW).optional();
        attached.ifPresent(a -> jdbc.sql("""
                UPDATE lecture_reports SET status='PROCESSING',error_message=NULL,updated_at=UTC_TIMESTAMP()+INTERVAL 9 HOUR
                WHERE id=? AND retry_used=? AND status='FAILED' AND error_message='REPORT_DISPATCH_UNKNOWN'
                """).params(a.reportId(), a.retryUsed()).update());
        return attached;
    }

    /** The first validated result wins even after an ambiguous first dispatch or an admitted retry. */
    @Transactional
    public boolean complete(Attempt expected, String content) {
        if (!lockReport(expected.attemptKey())) return false;
        var attempt = lockedRemote(expected);
        if (attempt.isEmpty()) return false;
        int won = jdbc.sql("""
                UPDATE lecture_reports SET status='COMPLETED', content=CAST(? AS JSON), error_message=NULL,
                       generated_at=UTC_TIMESTAMP()+INTERVAL 9 HOUR,updated_at=UTC_TIMESTAMP()+INTERVAL 9 HOUR
                WHERE id=? AND status IN ('PENDING','PROCESSING','FAILED')
                """).params(content, attempt.get().reportId()).update();
        finishAttempt(expected.attemptKey());
        return won == 1;
    }

    /** A definitive POST rejection may only fail the still-unattached submission. */
    @Transactional
    public void fail(UUID attemptKey, String reason, String diagnosticCode) {
        if (!lockReport(attemptKey)) return;
        var attempt = jdbc.sql(ATTEMPT + " WHERE attempt_key=? AND state='SUBMITTING' FOR UPDATE")
                .param(attemptKey.toString()).query(ATTEMPT_ROW).optional();
        attempt.ifPresent(current -> finishFailure(current, reason, diagnosticCode));
    }

    @Transactional
    public void failRemote(Attempt expected, String reason, String diagnosticCode) {
        if (lockReport(expected.attemptKey())) {
            lockedRemote(expected).ifPresent(current -> finishFailure(current, reason, diagnosticCode));
        }
    }

    private Optional<Attempt> lockedRemote(Attempt expected) {
        return jdbc.sql(ATTEMPT + " WHERE attempt_key=? AND response_id=? AND state='REMOTE' FOR UPDATE")
                .params(expected.attemptKey().toString(), expected.responseId()).query(ATTEMPT_ROW).optional();
    }

    private void finishFailure(Attempt current, String reason, String diagnosticCode) {
        failCurrent(current, reason, diagnosticCode);
        finishAttempt(current.attemptKey());
    }

    /** A lost POST response is not evidence that the provider discarded the request. Keep its snapshot. */
    @Transactional
    public void unknown(UUID attemptKey) {
        if (lockReport(attemptKey)) markUnknown(attemptKey);
    }

    @Transactional
    public void unbindUnverifiedResponse(UUID attemptKey, String responseId) {
        if (!lockReport(attemptKey)) return;
        int unbound = jdbc.sql("""
                UPDATE report_generation_attempts SET state='SUBMITTING',response_id=NULL
                WHERE attempt_key=? AND response_id=? AND state='REMOTE'
                """).params(attemptKey.toString(), responseId).update();
        if (unbound == 1) markUnknown(attemptKey);
    }

    @Transactional
    public void expireUnknownSnapshots(int limit) {
        var expired = jdbc.sql("""
                SELECT attempt_key FROM report_generation_attempts
                WHERE state='UNKNOWN' AND next_action_at <= UTC_TIMESTAMP(6)
                ORDER BY next_action_at,report_id LIMIT ?
                """).param(limit).query(String.class).list();
        for (var key : expired) {
            var attemptKey = UUID.fromString(key);
            if (lockReport(attemptKey)) expireIfDue(attemptKey);
        }
    }

    private void expireIfDue(UUID key) {
        int expired = jdbc.sql("""
                UPDATE report_generation_attempts SET state='EXPIRED',input_json=NULL
                WHERE attempt_key=? AND state='UNKNOWN' AND next_action_at <= UTC_TIMESTAMP(6)
                """).param(key.toString()).update();
        if (expired == 1) {
            jdbc.sql("""
                    UPDATE lecture_reports r JOIN report_generation_attempts a ON a.report_id=r.id AND a.retry_used=r.retry_used
                    SET r.error_message='REPORT_DISPATCH_EXPIRED',r.updated_at=UTC_TIMESTAMP()+INTERVAL 9 HOUR
                    WHERE a.attempt_key=? AND r.status='FAILED' AND r.error_message='REPORT_DISPATCH_UNKNOWN'
                    """).param(key.toString()).update();
        }
    }

    @Transactional
    public void recoverAbandonedSubmissions(int limit) {
        String free = liveLockFree("report_id");
        List<String> keys = jdbc.sql("""
                SELECT attempt_key FROM report_generation_attempts WHERE state='SUBMITTING' AND %s
                ORDER BY report_id LIMIT ?
                """.formatted(free)).param(limit).query(String.class).list();
        for (String key : keys) {
            UUID attemptKey = UUID.fromString(key);
            if (lockReport(attemptKey)) markUnknown(attemptKey);
        }
    }

    static String liveLockFree(String reportIdColumn) {
        return "IS_FREE_LOCK(" + ReportGenerationRepository.LOCK_NAME.formatted(reportIdColumn) + ")=1";
    }

    private void markUnknown(UUID key) {
        var attempt = jdbc.sql(ATTEMPT + " WHERE attempt_key=? AND state='SUBMITTING' AND response_id IS NULL FOR UPDATE")
                .param(key.toString()).query(ATTEMPT_ROW).optional();
        if (attempt.isEmpty()) return;
        jdbc.sql("""
                UPDATE report_generation_attempts SET state='UNKNOWN',next_action_at=UTC_TIMESTAMP(6)+INTERVAL 30 DAY
                WHERE attempt_key=?
                """).param(key.toString()).update();
        failCurrent(attempt.get(), "REPORT_DISPATCH_UNKNOWN", "REPORT_DISPATCH_UNKNOWN");
        freeSlot(key);
    }

    // All transitions for competing attempts serialize on the same parent, including manual retry.
    private boolean lockReport(UUID key) {
        var reportId = jdbc.sql("SELECT report_id FROM report_generation_attempts WHERE attempt_key=?")
                .param(key.toString()).query(Long.class).optional();
        return reportId.isPresent() && jdbc.sql("SELECT id FROM lecture_reports WHERE id=? FOR UPDATE")
                .param(reportId.get()).query(Long.class).optional().isPresent();
    }

    private void failCurrent(Attempt attempt, String reason, String diagnosticCode) {
        int failed = jdbc.sql("""
                UPDATE lecture_reports SET status='FAILED',error_message=?,updated_at=UTC_TIMESTAMP()+INTERVAL 9 HOUR
                WHERE id=? AND retry_used=? AND status='PROCESSING'
                """).params(reason, attempt.reportId(), attempt.retryUsed()).update();
        if (failed == 1 && diagnosticCode != null) {
            int firstAlert = jdbc.sql("""
                    UPDATE report_generation_attempts SET failure_alerted=TRUE WHERE attempt_key=? AND failure_alerted=FALSE
                    """).param(attempt.attemptKey().toString()).update();
            if (firstAlert == 0) return;
            jdbc.sql("""
                    INSERT INTO report_alert_outbox(report_id,retry_used,diagnostic_code,created_at,next_attempt_at)
                    VALUES(?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE report_id=report_id
                    """).params(attempt.reportId(), attempt.retryUsed(), diagnosticCode).update();
        }
    }

    private void finishAttempt(UUID key) {
        jdbc.sql("""
                UPDATE report_generation_attempts SET state=IF(response_id IS NULL,'FINISHED','DELETE_PENDING'),
                       input_json=NULL,next_action_at=UTC_TIMESTAMP(6) WHERE attempt_key=?
                """).param(key.toString()).update();
        freeSlot(key);
    }

    private void freeSlot(UUID key) {
        jdbc.sql("UPDATE report_generation_slots SET attempt_key=NULL WHERE attempt_key=?").param(key.toString()).update();
    }

    public void enqueueWebhook(String responseId) {
        jdbc.sql("""
                INSERT INTO report_webhook_inbox(response_id,next_action_at) VALUES(?,UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE response_id=response_id
                """).param(responseId).update();
    }

    public void finishWebhook(String responseId) {
        jdbc.sql("DELETE FROM report_webhook_inbox WHERE response_id=?").param(responseId).update();
    }

    public void deferWebhook(String responseId) {
        defer("report_webhook_inbox", "TRUE", responseId);
    }

    public void finishDelete(String responseId) {
        jdbc.sql("""
                UPDATE report_generation_attempts SET state='FINISHED' WHERE response_id=? AND state='DELETE_PENDING'
                """).param(responseId).update();
    }

    public void deferDelete(String responseId) {
        defer("report_generation_attempts", "state='DELETE_PENDING'", responseId);
    }

    public void deferResponse(String responseId) {
        defer("report_generation_attempts", "state='REMOTE'", responseId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<String> dueWebhooks(int limit) {
        return claim("report_webhook_inbox", "TRUE", limit);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<String> dueResponses(int limit) {
        return claim("report_generation_attempts", "state='REMOTE'", limit);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<String> dueDeletes(int limit) {
        return claim("report_generation_attempts", "state='DELETE_PENDING'", limit);
    }

    // Only fixed internal table names and predicates enter these queries. Commit a fresh due time
    // before network I/O; a crashed worker leaves the item recoverable without occupying a DB lock.
    private List<String> claim(String table, String predicate, int limit) {
        List<String> ids = jdbc.sql("SELECT response_id FROM " + table + " WHERE " + predicate
                        + " AND next_action_at <= " + NOW + " ORDER BY next_action_at,response_id LIMIT ? FOR UPDATE SKIP LOCKED")
                .param(limit).query(String.class).list();
        if (!ids.isEmpty()) {
            jdbc.sql("UPDATE " + table + " SET next_action_at=" + NOW
                    + "+INTERVAL 30 SECOND WHERE response_id IN (:ids)").param("ids", ids).update();
        }
        return ids;
    }

    private void defer(String table, String predicate, String id) {
        jdbc.sql("UPDATE " + table + " SET next_action_at=" + NOW + "+INTERVAL 1 MINUTE WHERE response_id=? AND "
                + predicate).param(id).update();
    }
}
