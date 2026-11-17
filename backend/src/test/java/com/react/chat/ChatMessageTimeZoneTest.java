package com.react.chat;

import com.react.chat.dto.ChatMessageView;
import com.react.chat.service.ChatMessageService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// DB 연결 시간대가 달라도 received_at 이 항상 +09:00 시각으로 저장되는지 확인한다.
// 연결 시간대는 하위 클래스가 정하고, JVM 시간대는 실행 환경(로컬 Asia/Seoul, CI UTC)을 따른다.
abstract class ChatMessageTimeZoneTest {
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired ChatMessageService service;
    private long hostUser;
    private long room;
    private long participant;

    @BeforeEach
    void prepareRoom() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        hostUser = insert("INSERT INTO users(email,password_hash,name,created_at) VALUES (?, 'hash','강연자', NOW())",
                UUID.randomUUID() + "@example.com");
        room = insert("""
                INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at)
                VALUES (?, 'test', ?, 'LIVE', NOW())
                """, hostUser, UUID.randomUUID().toString());
        participant = insert("""
                INSERT INTO lecture_participants(lecture_room_id,nickname,participant_token_hash,joined_at)
                VALUES (?, '청중A', ?, NOW())
                """, room, UUID.randomUUID().toString());
    }

    @Test
    void savedAtFixedPlusNineRegardlessOfConnectionZone() {
        assertThat(jdbc.sql("SELECT @@session.time_zone").query(String.class).single())
                .as("연결 세션 시간대(%s) / JVM 시간대(%s)", connectionOffset(), TimeZone.getDefault().getID())
                .isEqualTo(connectionOffset());

        for (ChatMessageView saved : new ChatMessageView[]{
                service.saveFromParticipant(room, participant, "청중 메시지"),
                service.saveFromHost(room, hostUser, "강연자 메시지")}) {
            LocalDateTime stored = jdbc.sql("SELECT received_at FROM chat_messages WHERE id = ?")
                    .param(saved.messageId()).query(LocalDateTime.class).single();
            LocalDateTime nowAtPlusNine = LocalDateTime.now(ZoneOffset.ofHours(9));

            assertThat(stored).isBetween(nowAtPlusNine.minusMinutes(1), nowAtPlusNine.plusMinutes(1));
            assertThat(saved.receivedAt()).isEqualTo(stored.toInstant(ZoneOffset.ofHours(9)));
            assertThat(saved.receivedAt()).isBetween(Instant.now().minus(1, ChronoUnit.MINUTES),
                    Instant.now().plus(1, ChronoUnit.MINUTES));
        }
    }

    abstract String connectionOffset();

    private long insert(String sql, Object... params) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql(sql).params(params).update(keys);
        return keys.getKey().longValue();
    }
}
