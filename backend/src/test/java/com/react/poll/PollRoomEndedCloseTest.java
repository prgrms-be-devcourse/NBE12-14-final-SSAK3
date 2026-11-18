package com.react.poll;

import com.react.poll.service.PollService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 강연 종료 API 가 방 상태를 바꾸는 트랜잭션 안에서 부를 투표 마감 메서드를 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
class PollRoomEndedCloseTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired PollService service;
    @Autowired PlatformTransactionManager transactionManager;
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
    void closesOpenPollWithRoomEndedReason() {
        LocalDateTime now = LocalDateTime.now(SEOUL).withNano(0);
        long open = insertPoll(room, "OPEN", now.minusSeconds(10), now.plusMinutes(5));

        int closed = service.closeOpenPollsOnRoomEnded(room);

        assertThat(closed).isEqualTo(1);
        var row = row(open);
        assertThat(row.status()).isEqualTo("CLOSED");
        assertThat(row.closeReason()).isEqualTo("ROOM_ENDED");
        // 종료 시각은 제한 시간이 남아 있던 만큼 앞당겨진 마감 시각이다.
        assertThat(row.closedAt()).isNotNull().isBefore(row.closesAt());
    }

    @Test
    void expiredPollKeepsTimeoutReasonAndClosesAtItsDeadline() {
        LocalDateTime createdAt = LocalDateTime.now(SEOUL).minusMinutes(3).withNano(0);
        LocalDateTime closesAt = createdAt.plusSeconds(60);
        long expired = insertPoll(room, "OPEN", createdAt, closesAt);

        int closed = service.closeOpenPollsOnRoomEnded(room);

        // 제한 시간이 이미 지난 투표는 종료 때문이 아니라 시간 초과로 마감된 것으로 남긴다.
        assertThat(closed).isEqualTo(1);
        var row = row(expired);
        assertThat(row.status()).isEqualTo("CLOSED");
        assertThat(row.closeReason()).isEqualTo("TIMEOUT");
        assertThat(row.closedAt()).isEqualTo(closesAt);
    }

    @Test
    void leavesAlreadyClosedPollsAndOtherRoomsUntouched() {
        LocalDateTime now = LocalDateTime.now(SEOUL).withNano(0);
        long alreadyClosed = insertPoll(room, "CLOSED", now.minusMinutes(10), now.minusMinutes(9));
        jdbc.sql("UPDATE polls SET closed_at = ?, close_reason = 'HOST' WHERE id = ?")
                .params(now.minusMinutes(9), alreadyClosed).update();
        long otherRoom = newRoom();
        long otherOpen = insertPoll(otherRoom, "OPEN", now.minusSeconds(10), now.plusMinutes(5));

        int closed = service.closeOpenPollsOnRoomEnded(room);

        assertThat(closed).isZero();
        assertThat(row(alreadyClosed).closeReason()).isEqualTo("HOST");
        assertThat(row(otherOpen).status()).isEqualTo("OPEN");
    }

    @Test
    void secondCallIsHarmless() {
        LocalDateTime now = LocalDateTime.now(SEOUL).withNano(0);
        insertPoll(room, "OPEN", now.minusSeconds(10), now.plusMinutes(5));

        assertThat(service.closeOpenPollsOnRoomEnded(room)).isEqualTo(1);
        assertThat(service.closeOpenPollsOnRoomEnded(room)).isZero();
    }

    @Test
    void joinsCallersTransactionSoRollbackKeepsPollOpen() {
        LocalDateTime now = LocalDateTime.now(SEOUL).withNano(0);
        long open = insertPoll(room, "OPEN", now.minusSeconds(10), now.plusMinutes(5));

        // 종료 API 가 방 상태를 바꾸다 실패해 롤백되면 투표 마감도 함께 취소되어야 한다.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.closeOpenPollsOnRoomEnded(room);
            status.setRollbackOnly();
        });

        assertThat(row(open).status()).isEqualTo("OPEN");
    }

    private record PollRow(String status, String closeReason, LocalDateTime closedAt, LocalDateTime closesAt) { }

    private PollRow row(long pollId) {
        return jdbc.sql("SELECT status, close_reason, closed_at, closes_at FROM polls WHERE id = ?").param(pollId)
                .query((rs, n) -> new PollRow(rs.getString(1), rs.getString(2),
                        rs.getObject(3, LocalDateTime.class), rs.getObject(4, LocalDateTime.class)))
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
