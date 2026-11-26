package com.react.ai.repository;

import com.react.ai.domain.AiAnalysisStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AiQuestionGroupRepository {
    private final JdbcClient jdbc;

    public Optional<Owner> findOwner(long roomId) {
        return jdbc.sql("""
                SELECT u.id, u.email FROM lecture_rooms r JOIN users u ON u.id = r.host_user_id
                WHERE r.id = ?
                """).param(roomId).query(Owner.class).optional();
    }

    public Optional<QuestionGroup> findGroup(long roomId, long groupId) {
        return jdbc.sql("""
                SELECT g.id AS topic_group_id, g.analysis_id, g.topic, g.summary, g.category, a.status AS analysis_status,
                       g.answered_at, a.window_end_at, COUNT(m.id) AS message_count
                FROM ai_topic_groups g JOIN ai_analyses a ON a.id = g.analysis_id
                LEFT JOIN ai_topic_group_messages m ON m.topic_group_id = g.id
                WHERE a.lecture_room_id = ? AND g.id = ?
                GROUP BY g.id, g.analysis_id, g.topic, g.summary, g.category, a.status, g.answered_at, a.window_end_at
                """).params(roomId, groupId).query(QuestionGroup.class).optional();
    }

    public List<QuestionGroup> findQuestions(long roomId, boolean answered, Long cursor, int limit) {
        return jdbc.sql("""
                SELECT g.id AS topic_group_id, g.analysis_id, g.topic, g.summary, g.category, a.status AS analysis_status,
                       g.answered_at, a.window_end_at, COUNT(m.id) AS message_count
                FROM ai_topic_groups g JOIN ai_analyses a ON a.id = g.analysis_id
                LEFT JOIN ai_topic_group_messages m ON m.topic_group_id = g.id
                WHERE a.lecture_room_id = ? AND a.status = 'COMPLETED' AND g.category = 'QUESTION'
                  AND (g.answered_at IS NOT NULL) = ?
                  AND (? IS NULL OR (a.window_end_at, g.id) >
                      (SELECT ac.window_end_at, gc.id FROM ai_topic_groups gc
                       JOIN ai_analyses ac ON ac.id = gc.analysis_id WHERE gc.id = ? AND ac.lecture_room_id = ?))
                GROUP BY g.id, g.analysis_id, g.topic, g.summary, g.category, a.status, g.answered_at, a.window_end_at
                ORDER BY a.window_end_at ASC, g.id ASC LIMIT ?
                """).params(roomId, answered, cursor, cursor, roomId, limit).query(QuestionGroup.class).list();
    }

    public int changeAnswerStatus(long roomId, long groupId, long hostId, boolean answered) {
        if (answered) {
            return jdbc.sql("""
                    UPDATE ai_topic_groups g JOIN ai_analyses a ON a.id = g.analysis_id
                    SET g.answered_at = NOW(), g.answered_by_user_id = ?
                    WHERE g.id = ? AND a.lecture_room_id = ? AND a.status = 'COMPLETED'
                      AND g.category = 'QUESTION' AND g.answered_at IS NULL
                    """).params(hostId, groupId, roomId).update();
        }
        return jdbc.sql("""
                UPDATE ai_topic_groups g JOIN ai_analyses a ON a.id = g.analysis_id
                SET g.answered_at = NULL, g.answered_by_user_id = NULL
                WHERE g.id = ? AND a.lecture_room_id = ? AND a.status = 'COMPLETED'
                  AND g.category = 'QUESTION' AND g.answered_at IS NOT NULL
                """).params(groupId, roomId).update();
    }

    /** 조건부 UPDATE 뒤 현재 읽기로 응답 상태를 고정한다. */
    public Optional<AnswerState> findAnswerStateForUpdate(long roomId, long groupId) {
        return jdbc.sql("""
                SELECT g.category, a.status AS analysis_status, g.answered_at
                FROM ai_topic_groups g JOIN ai_analyses a ON a.id = g.analysis_id
                WHERE a.lecture_room_id = ? AND g.id = ?
                FOR UPDATE
                """).params(roomId, groupId).query(AnswerState.class).optional();
    }

    public record Owner(long id, String email) {}
    public record QuestionGroup(long topicGroupId, long analysisId, String topic, String summary,
                                String category, AiAnalysisStatus analysisStatus, LocalDateTime answeredAt, LocalDateTime windowEndAt,
                                long messageCount) {}
    public record AnswerState(String category, AiAnalysisStatus analysisStatus, LocalDateTime answeredAt) {}
}
