package com.react.ai;

import com.react.ai.domain.AiAnalysisFinalizationStatus;
import com.react.ai.repository.AiAnalysisFinalizationRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AiAnalysisFinalizationRepositoryIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired AiAnalysisFinalizationRepository finalizations;

    private AiAnalysisFinalizationRepository.EndedRoom room;
    private Flyway flyway;

    @BeforeEach
    void prepareDatabase() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single())
                .as("전체 후보 조회 테스트의 초기화는 react_test에서만 실행한다")
                .isEqualTo("react_test");
        flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        // 후보 조회는 방 ID가 아닌 전체 종료 방을 대상으로 하므로 이전 실행의 데이터도 제거한다.
        flyway.clean();
        flyway.migrate();

        String key = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO users(email, password_hash, name, created_at) VALUES (?, 'hash', 'host', NOW())")
                .param(key + "@example.com").update();
        long hostId = jdbc.sql("SELECT id FROM users WHERE email = ?").param(key + "@example.com")
                .query(Long.class).single();
        LocalDateTime startedAt = LocalDateTime.of(2026, 11, 18, 10, 0);
        LocalDateTime endedAt = startedAt.plusSeconds(75);
        jdbc.sql("""
                INSERT INTO lecture_rooms(host_user_id, title, join_code, status, created_at, started_at, ended_at)
                VALUES (?, '종료 강연', ?, 'ENDED', NOW(), ?, ?)
                """).params(hostId, key, startedAt, endedAt).update();
        long roomId = jdbc.sql("SELECT id FROM lecture_rooms WHERE join_code = ?").param(key).query(Long.class).single();
        room = new AiAnalysisFinalizationRepository.EndedRoom(roomId, startedAt, endedAt);
    }

    @AfterEach
    void cleanUp() {
        if (flyway != null) {
            flyway.clean();
        }
    }

    @Test
    void 종료_방을_한_작업자만_claim하고_lease_만료_후_새_작업자가_회수한다() {
        assertThat(finalizations.findCandidates(5)).contains(room);
        finalizations.ensurePending(room);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertThat(finalizations.claim(room, first, Duration.ofSeconds(90))).isTrue();
        assertThat(finalizations.claim(room, second, Duration.ofSeconds(90))).isFalse();
        jdbc.sql("""
                UPDATE ai_analysis_finalizations
                SET lease_until = DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND)
                WHERE lecture_room_id = ?
                """).param(room.roomId()).update();

        assertThat(finalizations.findCandidates(5)).contains(room);
        assertThat(finalizations.claim(room, second, Duration.ofSeconds(90))).isTrue();
        assertThat(finalizations.complete(room.roomId(), first, 0)).isFalse();
        assertThat(finalizations.complete(room.roomId(), second, 1)).isTrue();

        var completed = finalizations.find(room.roomId());
        assertThat(completed.status()).isEqualTo(AiAnalysisFinalizationStatus.COMPLETED);
        assertThat(completed.failedAnalysisCount()).isEqualTo(1);
        assertThat(completed.claimToken()).isNull();
        assertThat(finalizations.findCandidates(5)).doesNotContain(room);
    }

    @Test
    void 만료된_lease의_기존_작업자는_갱신하거나_완료할_수_없다() {
        finalizations.ensurePending(room);
        UUID worker = UUID.randomUUID();

        assertThat(finalizations.claim(room, worker, Duration.ofSeconds(90))).isTrue();
        jdbc.sql("""
                UPDATE ai_analysis_finalizations
                SET lease_until = DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND)
                WHERE lecture_room_id = ?
                """).param(room.roomId()).update();

        assertThat(finalizations.renewLease(room.roomId(), worker, Duration.ofSeconds(90))).isFalse();
        assertThat(finalizations.complete(room.roomId(), worker, 0)).isFalse();

        var processing = finalizations.find(room.roomId());
        assertThat(processing.status()).isEqualTo(AiAnalysisFinalizationStatus.PROCESSING);
        assertThat(processing.claimToken()).isEqualTo(worker.toString());
    }
}
