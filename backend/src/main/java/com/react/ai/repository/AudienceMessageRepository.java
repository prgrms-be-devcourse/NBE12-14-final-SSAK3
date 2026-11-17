package com.react.ai.repository;

import com.react.ai.dto.AudienceMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class AudienceMessageRepository {
    private final JdbcClient jdbc;

    public List<AudienceMessage> findAudienceMessages(long roomId, LocalDateTime windowStartAt, LocalDateTime windowEndAt) {
        return jdbc.sql("""
                SELECT id AS message_id, lecture_room_id AS room_id, content, received_at
                FROM chat_messages
                WHERE lecture_room_id = ?
                  AND participant_id IS NOT NULL
                  AND received_at >= ?
                  AND received_at < ?
                ORDER BY received_at, id
                """).params(roomId, windowStartAt, windowEndAt).query(AudienceMessage.class).list();
    }
}
