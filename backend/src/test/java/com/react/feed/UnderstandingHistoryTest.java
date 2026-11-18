package com.react.feed;

import com.react.feed.dto.UnderstandingStatus;
import com.react.feed.service.UnderstandingService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static com.react.feed.dto.UnderstandingStatus.CONFUSED;
import static com.react.feed.dto.UnderstandingStatus.NOT_UNDERSTOOD;
import static com.react.feed.dto.UnderstandingStatus.UNDERSTOOD;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class UnderstandingHistoryTest {
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired UnderstandingService service;
    private long room;
    private long participant;

    @BeforeEach
    void prepareRoom() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        room = newRoom(newUser("강연자"), "LIVE");
        participant = newParticipant(room, "청중A");
    }

    @Test
    void historyRecordsPreviousNewAndChangedAt() {
        var first = service.change(room, participant, UNDERSTOOD);
        var second = service.change(room, participant, NOT_UNDERSTOOD);

        var rows = jdbc.sql("SELECT previous_status, new_status, changed_at FROM understanding_histories WHERE participant_id = ? ORDER BY id")
                .param(participant).query().listOfRows();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("previous_status")).isNull();
        assertThat(rows.get(0).get("new_status")).isEqualTo("UNDERSTOOD");
        assertThat(rows.get(1).get("previous_status")).isEqualTo("UNDERSTOOD");
        assertThat(rows.get(1).get("new_status")).isEqualTo("NOT_UNDERSTOOD");
        // DATETIME 은 +09:00 기준으로 저장되고, 응답의 updatedAt 과 같은 시각이다.
        assertThat(((LocalDateTime) rows.get(0).get("changed_at")).toInstant(ZoneOffset.ofHours(9))).isEqualTo(first.updatedAt());
        assertThat(((LocalDateTime) rows.get(1).get("changed_at")).toInstant(ZoneOffset.ofHours(9))).isEqualTo(second.updatedAt());
    }

    @Test
    void historyIsContiguousUnderConcurrentChanges() throws Exception {
        UnderstandingStatus[] choices = {UNDERSTOOD, CONFUSED, NOT_UNDERSTOOD};
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Object>> calls = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                UnderstandingStatus choice = choices[i % 3];
                calls.add(() -> service.change(room, participant, choice));
            }
            for (var future : pool.invokeAll(calls)) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        var rows = jdbc.sql("SELECT previous_status, new_status FROM understanding_histories WHERE participant_id = ? ORDER BY id")
                .param(participant).query().listOfRows();
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).get("previous_status")).isNull();
        for (int i = 0; i < rows.size(); i++) {
            // 이어지는 이력의 previous 는 직전 이력의 new 와 같고, 같은 상태로의 "변경"은 기록되지 않는다.
            if (i > 0) {
                assertThat(rows.get(i).get("previous_status")).isEqualTo(rows.get(i - 1).get("new_status"));
            }
            assertThat(rows.get(i).get("new_status")).isNotEqualTo(rows.get(i).get("previous_status"));
        }
        String last = (String) rows.get(rows.size() - 1).get("new_status");
        assertThat(service.getMine(room, participant).status().name()).isEqualTo(last);
    }

    private long newUser(String name) {
        return insert("INSERT INTO users(email,password_hash,name,created_at) VALUES (?, 'hash', ?, NOW())",
                UUID.randomUUID() + "@example.com", name);
    }

    private long newRoom(long hostUserId, String status) {
        return insert("""
                INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at)
                VALUES (?, 'test', ?, ?, NOW())
                """, hostUserId, UUID.randomUUID().toString(), status);
    }

    private long newParticipant(long roomId, String nickname) {
        return insert("""
                INSERT INTO lecture_participants(lecture_room_id,nickname,participant_token_hash,joined_at)
                VALUES (?, ?, ?, NOW())
                """, roomId, nickname, UUID.randomUUID().toString());
    }

    private long insert(String sql, Object... params) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql(sql).params(params).update(keys);
        return keys.getKey().longValue();
    }
}
