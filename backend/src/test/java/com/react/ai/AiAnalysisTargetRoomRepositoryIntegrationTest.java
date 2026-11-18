package com.react.ai;

import com.react.ai.repository.AiAnalysisTargetRoomRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AiAnalysisTargetRoomRepositoryIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired AiAnalysisTargetRoomRepository rooms;

    @Test
    void LIVE이고_startedAt이_있는_강연방만_조회한다() {
        String key = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO users(email, password_hash, name, created_at) VALUES (?, 'hash', 'host', NOW())")
                .param(key + "@example.com").update();
        long hostId = jdbc.sql("SELECT id FROM users WHERE email = ?").param(key + "@example.com")
                .query(Long.class).single();
        LocalDateTime startedAt = LocalDateTime.of(2026, 11, 18, 10, 0);
        long eligibleId = insertRoom(hostId, key + "-live", "LIVE", startedAt);
        long liveWithoutStartId = insertRoom(hostId, key + "-live-no-start", "LIVE", null);
        long readyId = insertRoom(hostId, key + "-ready", "READY", startedAt);
        long endedId = insertRoom(hostId, key + "-ended", "ENDED", startedAt);

        assertThat(rooms.findLiveRoomsWithStartedAt())
                .anySatisfy(room -> {
                    assertThat(room.roomId()).isEqualTo(eligibleId);
                    assertThat(room.startedAt()).isEqualTo(startedAt);
                })
                .extracting(AiAnalysisTargetRoomRepository.LiveRoom::roomId)
                .doesNotContain(liveWithoutStartId, readyId, endedId);
    }

    private long insertRoom(long hostId, String joinCode, String status, LocalDateTime startedAt) {
        jdbc.sql("""
                INSERT INTO lecture_rooms(host_user_id, title, join_code, status, created_at, started_at)
                VALUES (?, '강연', ?, ?, NOW(), ?)
                """).params(hostId, joinCode, status, startedAt).update();
        return jdbc.sql("SELECT id FROM lecture_rooms WHERE join_code = ?").param(joinCode).query(Long.class).single();
    }
}
