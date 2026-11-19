package com.react.ai.repository;

import com.react.ai.domain.AiAnalysisFinalizationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class AiAnalysisFinalizationRepository {
    private final JdbcClient jdbc;

    public List<EndedRoom> findCandidates(int limit) {
        return jdbc.sql("""
                SELECT r.id AS room_id, r.started_at, r.ended_at
                FROM lecture_rooms r
                LEFT JOIN ai_analysis_finalizations f ON f.lecture_room_id = r.id
                WHERE r.status = 'ENDED'
                  AND r.started_at IS NOT NULL
                  AND r.ended_at IS NOT NULL
                  AND (f.lecture_room_id IS NULL
                       OR f.status = 'PENDING'
                       OR (f.status = 'PROCESSING' AND f.lease_until < CURRENT_TIMESTAMP))
                ORDER BY r.ended_at, r.id
                LIMIT ?
                """).param(limit).query(EndedRoom.class).list();
    }

    public void ensurePending(EndedRoom room) {
        jdbc.sql("""
                INSERT IGNORE INTO ai_analysis_finalizations(
                    lecture_room_id, started_at, ended_at, status, failed_analysis_count,
                    created_at, updated_at
                ) VALUES (?, ?, ?, 'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """).params(room.roomId(), room.startedAt(), room.endedAt()).update();
    }

    public boolean claim(EndedRoom room, UUID claimToken, Duration leaseDuration) {
        return jdbc.sql("""
                UPDATE ai_analysis_finalizations
                SET status = 'PROCESSING', claim_token = ?,
                    lease_until = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL ? SECOND),
                    last_error = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE lecture_room_id = ?
                  AND started_at = ?
                  AND ended_at = ?
                  AND (status = 'PENDING'
                       OR (status = 'PROCESSING' AND lease_until < CURRENT_TIMESTAMP))
                """).params(claimToken.toString(), leaseDuration.toSeconds(), room.roomId(), room.startedAt(), room.endedAt())
                .update() == 1;
    }

    public boolean renewLease(long roomId, UUID claimToken, Duration leaseDuration) {
        return jdbc.sql("""
                UPDATE ai_analysis_finalizations
                SET lease_until = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL ? SECOND), updated_at = CURRENT_TIMESTAMP
                WHERE lecture_room_id = ?
                  AND status = 'PROCESSING'
                  AND claim_token = ?
                  AND lease_until > CURRENT_TIMESTAMP
                """).params(leaseDuration.toSeconds(), roomId, claimToken.toString()).update() == 1;
    }

    public boolean release(long roomId, UUID claimToken) {
        return jdbc.sql("""
                UPDATE ai_analysis_finalizations
                SET status = 'PENDING', claim_token = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE lecture_room_id = ? AND status = 'PROCESSING' AND claim_token = ?
                """).params(roomId, claimToken.toString()).update() == 1;
    }

    public boolean complete(long roomId, UUID claimToken, int failedAnalysisCount) {
        return jdbc.sql("""
                UPDATE ai_analysis_finalizations
                SET status = 'COMPLETED', claim_token = NULL, lease_until = NULL,
                    failed_analysis_count = ?, completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE lecture_room_id = ?
                  AND status = 'PROCESSING'
                  AND claim_token = ?
                  AND lease_until > CURRENT_TIMESTAMP
                """).params(failedAnalysisCount, roomId, claimToken.toString()).update() == 1;
    }

    public boolean markFailed(long roomId, UUID claimToken, String errorCode) {
        return jdbc.sql("""
                UPDATE ai_analysis_finalizations
                SET status = 'FAILED', claim_token = NULL, lease_until = NULL,
                    last_error = ?, completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE lecture_room_id = ? AND status = 'PROCESSING' AND claim_token = ?
                """).params(errorCode, roomId, claimToken.toString()).update() == 1;
    }

    public Finalization find(long roomId) {
        return jdbc.sql("""
                SELECT lecture_room_id AS room_id, started_at, ended_at, status, claim_token, lease_until,
                       failed_analysis_count, last_error, completed_at
                FROM ai_analysis_finalizations
                WHERE lecture_room_id = ?
                """).param(roomId).query(Finalization.class).single();
    }

    public record EndedRoom(long roomId, LocalDateTime startedAt, LocalDateTime endedAt) {
    }

    public record Finalization(long roomId, LocalDateTime startedAt, LocalDateTime endedAt,
                               AiAnalysisFinalizationStatus status, String claimToken, LocalDateTime leaseUntil,
                               int failedAnalysisCount, String lastError, LocalDateTime completedAt) {
    }
}
