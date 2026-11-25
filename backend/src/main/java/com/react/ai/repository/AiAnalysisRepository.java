package com.react.ai.repository;

import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.dto.AiAnalysisResult;
import com.react.ai.dto.AiTopicResult;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class AiAnalysisRepository {
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public AcquiredAnalysis acquire(long roomId, LocalDateTime windowStartAt, LocalDateTime windowEndAt) {
        try {
            jdbc.sql("""
                    INSERT INTO ai_analyses(lecture_room_id, window_start_at, window_end_at, status, created_at)
                    VALUES (?, ?, ?, 'PENDING', CURRENT_TIMESTAMP)
                    """).params(roomId, windowStartAt, windowEndAt).update();
            return new AcquiredAnalysis(find(roomId, windowStartAt, windowEndAt).orElseThrow(), true);
        } catch (DuplicateKeyException e) {
            return new AcquiredAnalysis(find(roomId, windowStartAt, windowEndAt).orElseThrow(), false);
        }
    }

    public boolean markProcessing(long analysisId) {
        return jdbc.sql("""
                UPDATE ai_analyses
                SET status = 'PROCESSING', processing_started_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PENDING'
                """).param(analysisId).update() == 1;
    }

    /** 재시도 직전에 분석이 여전히 처리 중인지 확인한다. */
    public boolean isProcessing(long analysisId) {
        return jdbc.sql("""
                SELECT EXISTS(
                    SELECT 1
                    FROM ai_analyses
                    WHERE id = ? AND status = 'PROCESSING'
                )
                """).param(analysisId).query(Boolean.class).single();
    }

    public boolean completeEmpty(long analysisId) {
        return jdbc.sql("""
                UPDATE ai_analyses
                SET status = 'COMPLETED', model_name = NULL, error_message = NULL, completed_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PROCESSING'
                """).param(analysisId).update() == 1;
    }

    /** 빈 청중 채팅 구간은 COMPLETED이면서 모델명이 없는 상태로 보존한다. */
    public boolean isCompletedEmpty(long analysisId) {
        return jdbc.sql("""
                SELECT EXISTS(
                    SELECT 1 FROM ai_analyses
                    WHERE id = ? AND status = 'COMPLETED' AND model_name IS NULL
                )
                """).param(analysisId).query(Boolean.class).single();
    }

    @Transactional
    public void saveCompleted(long analysisId, AiAnalysisResult result, String modelName) {
        for (AiTopicResult topic : result.topics()) {
            long topicGroupId = insertTopicGroup(analysisId, topic);
            for (long messageId : topic.sourceMessageIds()) {
                jdbc.sql("""
                        INSERT INTO ai_topic_group_messages(topic_group_id, chat_message_id)
                        VALUES (?, ?)
                        """).params(topicGroupId, messageId).update();
            }
        }
        int updated = jdbc.sql("""
                UPDATE ai_analyses
                SET status = 'COMPLETED', model_name = ?, error_message = NULL, completed_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PROCESSING'
                """).params(modelName, analysisId).update();
        if (updated != 1) {
            throw new IllegalStateException("분석 완료 상태로 변경할 수 없습니다.");
        }
    }

    public boolean markFailed(long analysisId, String errorMessage) {
        return jdbc.sql("""
                UPDATE ai_analyses
                SET status = 'FAILED', error_message = ?, completed_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PROCESSING'
                """).params(errorMessage, analysisId).update() == 1;
    }

    /** 서버 중단 등으로 남은 미완료 분석은 재호출하지 않고 종결해 후속 처리를 막지 않는다. */
    /** PENDING은 생성 시각, PROCESSING은 실제 처리 시작 시각을 기준으로 중단 작업을 종결한다. */
    public int abortStaleInFlight(Duration staleThreshold) {
        return jdbc.sql("""
                UPDATE ai_analyses
                SET status = 'FAILED', error_message = 'STALE_ANALYSIS_ABORTED', completed_at = CURRENT_TIMESTAMP
                WHERE (status = 'PENDING'
                       AND created_at <= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL ? SECOND))
                   OR (status = 'PROCESSING'
                       AND COALESCE(processing_started_at, created_at)
                           <= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL ? SECOND))
                """).params(staleThreshold.toSeconds(), staleThreshold.toSeconds()).update();
    }

    public Optional<Analysis> find(long roomId, LocalDateTime windowStartAt, LocalDateTime windowEndAt) {
        return jdbc.sql("""
                SELECT id, lecture_room_id AS room_id, window_start_at, window_end_at, status
                FROM ai_analyses
                WHERE lecture_room_id = ? AND window_start_at = ? AND window_end_at = ?
                """).params(roomId, windowStartAt, windowEndAt).query(Analysis.class).optional();
    }

    public List<Analysis> findWithin(long roomId, LocalDateTime fromInclusive, LocalDateTime toInclusive) {
        return jdbc.sql("""
                SELECT id, lecture_room_id AS room_id, window_start_at, window_end_at, status
                FROM ai_analyses
                WHERE lecture_room_id = ?
                  AND window_start_at >= ?
                  AND window_end_at <= ?
                """).params(roomId, fromInclusive, toInclusive).query(Analysis.class).list();
    }

    /** 이미 생성된 상태(PENDING/PROCESSING/COMPLETED/FAILED)를 모두 반환해 스케줄러 재호출을 막는다. */
    public Set<LocalDateTime> findRecordedWindowStarts(long roomId, LocalDateTime fromInclusive,
                                                        LocalDateTime toExclusive) {
        return new HashSet<>(jdbc.sql("""
                SELECT window_start_at
                FROM ai_analyses
                WHERE lecture_room_id = ?
                  AND window_start_at >= ?
                  AND window_start_at < ?
                """).params(roomId, fromInclusive, toExclusive).query(LocalDateTime.class).list());
    }

    private long insertTopicGroup(long analysisId, AiTopicResult topic) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO ai_topic_groups(analysis_id, topic, category, summary, created_at)
                    VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, analysisId);
            statement.setString(2, topic.topic());
            statement.setString(3, topic.category().name());
            statement.setString(4, topic.summary());
            return statement;
        }, keyHolder);
        if (keyHolder.getKey() == null) {
            throw new IllegalStateException("주제 그룹 생성 키를 확인할 수 없습니다.");
        }
        return keyHolder.getKey().longValue();
    }

    public record AcquiredAnalysis(Analysis analysis, boolean created) {
    }

    public record Analysis(long id, long roomId, LocalDateTime windowStartAt, LocalDateTime windowEndAt,
                           AiAnalysisStatus status) {
    }
}
