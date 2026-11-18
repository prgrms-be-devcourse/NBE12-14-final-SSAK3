package com.react.ai.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class AiAnalysisTargetRoomRepository {
    private final JdbcClient jdbc;

    public List<LiveRoom> findLiveRoomsWithStartedAt() {
        return jdbc.sql("""
                SELECT id AS room_id, started_at
                FROM lecture_rooms
                WHERE status = 'LIVE'
                  AND started_at IS NOT NULL
                ORDER BY id
                """).query(LiveRoom.class).list();
    }

    public record LiveRoom(long roomId, LocalDateTime startedAt) {
    }
}
