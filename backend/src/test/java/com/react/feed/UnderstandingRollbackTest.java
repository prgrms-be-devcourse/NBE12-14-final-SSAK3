package com.react.feed;

import com.react.feed.dto.UnderstandingStatus;
import com.react.feed.entity.UnderstandingHistory;
import com.react.feed.repository.UnderstandingHistoryRepository;
import com.react.feed.service.UnderstandingService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.UUID;

import static com.react.feed.dto.UnderstandingStatus.CONFUSED;
import static com.react.feed.dto.UnderstandingStatus.UNDERSTOOD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest
@ActiveProfiles("test")
class UnderstandingRollbackTest {
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired UnderstandingService service;
    @MockitoSpyBean UnderstandingHistoryRepository histories;
    private long room;
    private long participant;

    @BeforeEach
    void prepareRoom() {
        reset(histories);
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        room = newRoom(newUser("강연자"), "LIVE");
        participant = newParticipant(room, "청중A");
    }

    @Test
    void failedHistorySaveRollsBackFirstChoice() {
        doThrow(new IllegalStateException("history failed")).when(histories).save(any(UnderstandingHistory.class));

        assertThatThrownBy(() -> service.change(room, participant, CONFUSED)).isInstanceOf(IllegalStateException.class);

        assertThat(service.getMine(room, participant).status()).isNull();
        assertThat(count("participant_understandings")).isZero();
        assertThat(count("understanding_histories")).isZero();
    }

    @Test
    void failedHistorySaveKeepsPreviousStatus() {
        service.change(room, participant, UNDERSTOOD);
        doThrow(new IllegalStateException("history failed")).when(histories).save(any(UnderstandingHistory.class));

        assertThatThrownBy(() -> service.change(room, participant, CONFUSED)).isInstanceOf(IllegalStateException.class);

        UnderstandingStatus status = service.getMine(room, participant).status();
        assertThat(status).isEqualTo(UNDERSTOOD);
        assertThat(count("understanding_histories")).isEqualTo(1);
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE participant_id = ?")
                .param(participant).query(Long.class).single();
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
