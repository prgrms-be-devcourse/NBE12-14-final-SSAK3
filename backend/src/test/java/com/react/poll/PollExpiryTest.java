package com.react.poll;

import com.react.poll.dto.PollLifecycleEvent;
import com.react.poll.entity.PollCloseReason;
import com.react.poll.service.PollExpiryProcessor;
import com.react.poll.service.PollLifecycleChangedEvent;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 제한 시간이 지난 투표를 서버가 직접 TIMEOUT 으로 마감하고, 마감을 한 번만 알리는지 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
@RecordApplicationEvents
class PollExpiryTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired PollExpiryProcessor processor;
    @Autowired ApplicationEvents applicationEvents;
    private long hostUserId;
    private long room;

    @BeforeEach
    void prepareRoom() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        hostUserId = insert("INSERT INTO users(email,password_hash,name,created_at) VALUES (?, 'hash', '강연자', ?)",
                UUID.randomUUID() + "@example.com", LocalDateTime.now(SEOUL));
        room = newRoom();
    }

    @Test
    void closesAnExpiredOpenPollAtItsDeadlineAndAnnouncesIt() {
        LocalDateTime createdAt = LocalDateTime.now(SEOUL).minusMinutes(3).withNano(0);
        LocalDateTime closesAt = createdAt.plusSeconds(60);
        long expired = insertPoll(room, "OPEN", createdAt, closesAt);

        processor.closeExpiredPolls();

        var row = row(expired);
        assertThat(row.status()).isEqualTo("CLOSED");
        assertThat(row.closeReason()).isEqualTo("TIMEOUT");
        assertThat(row.closedAt()).isEqualTo(closesAt);
        assertThat(row.closedTotalParticipants()).isNotNull();
        var events = changes();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).payload().type()).isEqualTo("POLL_CLOSED");
        var closed = (PollLifecycleEvent.Closed) events.get(0).payload().data();
        assertThat(closed.pollId()).isEqualTo(expired);
        assertThat(closed.roomId()).isEqualTo(room);
        assertThat(closed.closeReason()).isEqualTo(PollCloseReason.TIMEOUT);
        assertThat(closed.closedAt()).isEqualTo(closesAt.atZone(SEOUL).toInstant().toString());
    }

    @Test
    void leavesPollsWithTimeLeftAlone() {
        LocalDateTime now = LocalDateTime.now(SEOUL).withNano(0);
        long running = insertPoll(room, "OPEN", now.minusSeconds(10), now.plusMinutes(5));

        processor.closeExpiredPolls();

        assertThat(row(running).status()).isEqualTo("OPEN");
        assertThat(changes()).isEmpty();
    }

    @Test
    void doesNotTouchPollsThatAlreadyClosed() {
        LocalDateTime now = LocalDateTime.now(SEOUL).withNano(0);
        long byHost = insertPoll(room, "CLOSED", now.minusMinutes(10), now.minusMinutes(9));
        jdbc.sql("UPDATE polls SET closed_at = ?, close_reason = 'HOST' WHERE id = ?")
                .params(now.minusMinutes(9), byHost).update();

        processor.closeExpiredPolls();

        assertThat(row(byHost).closeReason()).isEqualTo("HOST");
        assertThat(changes()).isEmpty();
    }

    @Test
    void anExpiredPollIsAnnouncedOnlyOnce() {
        LocalDateTime now = LocalDateTime.now(SEOUL).withNano(0);
        insertPoll(room, "OPEN", now.minusMinutes(3), now.minusMinutes(2));

        processor.closeExpiredPolls();
        processor.closeExpiredPolls();

        assertThat(changes()).hasSize(1);
    }

    private List<PollLifecycleChangedEvent> changes() {
        return applicationEvents.stream(PollLifecycleChangedEvent.class).filter(event -> event.roomId() == room).toList();
    }

    private record PollRow(String status, String closeReason, LocalDateTime closedAt, Long closedTotalParticipants) { }

    private PollRow row(long pollId) {
        return jdbc.sql("SELECT status, close_reason, closed_at, closed_total_participants FROM polls WHERE id = ?")
                .param(pollId)
                .query((rs, n) -> new PollRow(rs.getString(1), rs.getString(2),
                        rs.getObject(3, LocalDateTime.class), (Long) rs.getObject(4)))
                .single();
    }

    private long newRoom() {
        return insert("""
                INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at)
                VALUES (?, 'test', ?, 'LIVE', ?)
                """, hostUserId, UUID.randomUUID().toString(), LocalDateTime.now(SEOUL));
    }

    private long insertPoll(long roomId, String status, LocalDateTime createdAt, LocalDateTime closesAt) {
        return insert("""
                INSERT INTO polls(lecture_room_id,type,question,status,created_at,closes_at)
                VALUES (?, 'GENERAL', '질문', ?, ?, ?)
                """, roomId, status, createdAt, closesAt);
    }

    private long insert(String sql, Object... params) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql(sql).params(params).update(keys);
        return keys.getKey().longValue();
    }
}
