package com.react.ai.repository;

import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.domain.TopicCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AiAnalysisQueryRepository {
    private final JdbcClient jdbc;

    public Optional<RoomOwner> findRoomOwner(long roomId) {
        return jdbc.sql("""
                SELECT u.email
                FROM lecture_rooms r
                JOIN users u ON u.id = r.host_user_id
                WHERE r.id = ?
                """).param(roomId).query(RoomOwner.class).optional();
    }

    public List<AnalysisRow> findRecentAnalyses(long roomId, int limit) {
        return jdbc.sql("""
                SELECT id AS analysis_id, window_start_at, window_end_at, status, completed_at
                FROM ai_analyses
                WHERE lecture_room_id = ?
                ORDER BY window_start_at DESC, id DESC
                LIMIT ?
                """).params(roomId, limit).query(AnalysisRow.class).list();
    }

    /** AFTER_COMMIT 방송용 조회다. FAILED/PENDING/PROCESSING 분석은 이벤트 대상이 아니다. */
    public Optional<AnalysisRow> findCompletedAnalysis(long roomId, long analysisId) {
        return jdbc.sql("""
                SELECT id AS analysis_id, window_start_at, window_end_at, status, completed_at
                FROM ai_analyses
                WHERE lecture_room_id = ? AND id = ? AND status = 'COMPLETED'
                """).params(roomId, analysisId).query(AnalysisRow.class).optional();
    }

    public Optional<LocalDateTime> findLastCompletedAt(long roomId) {
        return jdbc.sql("""
                SELECT MAX(completed_at)
                FROM ai_analyses
                WHERE lecture_room_id = ? AND status = 'COMPLETED'
                """).param(roomId).query(LocalDateTime.class).optional();
    }

    public List<TopicGroupRow> findTopicGroups(Collection<Long> analysisIds) {
        if (analysisIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT g.id AS topic_group_id, g.analysis_id, g.topic, g.category, g.summary,
                       COUNT(m.id) AS message_count
                FROM ai_topic_groups g
                LEFT JOIN ai_topic_group_messages m ON m.topic_group_id = g.id
                WHERE g.analysis_id IN (%s)
                GROUP BY g.id, g.analysis_id, g.topic, g.category, g.summary
                ORDER BY g.analysis_id, g.id
                """.formatted(placeholders(analysisIds.size())))
                .params(analysisIds.toArray()).query(TopicGroupRow.class).list();
    }

    public List<TopicGroupMessageIdRow> findTopicGroupMessageIds(Collection<Long> topicGroupIds) {
        if (topicGroupIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT topic_group_id, chat_message_id
                FROM ai_topic_group_messages
                WHERE topic_group_id IN (%s)
                ORDER BY topic_group_id, chat_message_id
                """.formatted(placeholders(topicGroupIds.size())))
                .params(topicGroupIds.toArray()).query(TopicGroupMessageIdRow.class).list();
    }

    public Optional<TopicGroupHeader> findTopicGroupInRoom(long roomId, long topicGroupId) {
        return jdbc.sql("""
                SELECT g.id AS topic_group_id, g.topic
                FROM ai_topic_groups g
                JOIN ai_analyses a ON a.id = g.analysis_id
                WHERE a.lecture_room_id = ? AND g.id = ?
                """).params(roomId, topicGroupId).query(TopicGroupHeader.class).optional();
    }

    public List<SourceMessageRow> findTopicGroupMessages(long roomId, long topicGroupId) {
        return jdbc.sql("""
                SELECT c.id AS message_id, c.participant_id, p.nickname, c.content, c.received_at
                FROM ai_topic_group_messages m
                JOIN ai_topic_groups g ON g.id = m.topic_group_id
                JOIN ai_analyses a ON a.id = g.analysis_id
                JOIN chat_messages c ON c.id = m.chat_message_id AND c.lecture_room_id = a.lecture_room_id
                JOIN lecture_participants p ON p.id = c.participant_id
                WHERE a.lecture_room_id = ? AND m.topic_group_id = ?
                ORDER BY c.received_at, c.id
                """).params(roomId, topicGroupId).query(SourceMessageRow.class).list();
    }

    private String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    public record RoomOwner(String email) {
    }

    public record AnalysisRow(long analysisId, LocalDateTime windowStartAt, LocalDateTime windowEndAt,
                              AiAnalysisStatus status, LocalDateTime completedAt) {
    }

    public record TopicGroupRow(long topicGroupId, long analysisId, String topic, TopicCategory category,
                                String summary, long messageCount) {
    }

    public record TopicGroupMessageIdRow(long topicGroupId, long chatMessageId) {
    }

    public record TopicGroupHeader(long topicGroupId, String topic) {
    }

    public record SourceMessageRow(long messageId, long participantId, String nickname, String content,
                                   LocalDateTime receivedAt) {
    }
}
