package com.react.feed;

import com.react.feed.service.UnderstandingService;
import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.react.feed.dto.UnderstandingStatus.CONFUSED;
import static com.react.feed.dto.UnderstandingStatus.UNDERSTOOD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 강연 종료와 이해도 변경이 동시에 일어나도 종료 이후에는 저장되지 않는지 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
class UnderstandingRoomEndRaceTest {
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired UnderstandingService service;
    @Autowired PlatformTransactionManager transactionManager;
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
    void changeWaitsForUncommittedEndAndIsRejectedAfterIt() throws Exception {
        CountDownLatch endUpdated = new CountDownLatch(1);
        CountDownLatch commitEnd = new CountDownLatch(1);

        // 종료 트랜잭션: 방 행을 ENDED 로 바꾸고 커밋하지 않은 채 붙잡아 둔다.
        CompletableFuture<Void> ending = CompletableFuture.runAsync(() ->
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jdbc.sql("UPDATE lecture_rooms SET status = 'ENDED' WHERE id = ?").param(room).update();
                    endUpdated.countDown();
                    await(commitEnd);
                }));
        assertThat(endUpdated.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Object> changing = CompletableFuture.supplyAsync(() -> service.change(room, participant, CONFUSED));

        // 종료가 커밋되기 전에는 변경이 방 행 잠금을 기다린다.
        assertThatThrownBy(() -> changing.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);

        commitEnd.countDown();
        ending.get(5, TimeUnit.SECONDS);

        assertThatThrownBy(() -> changing.get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(BusinessException.class)
                .rootCause().satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.ROOM_ENDED));
        assertThat(count("participant_understandings")).isZero();
        assertThat(count("understanding_histories")).isZero();
    }

    @Test
    void endWaitsForInFlightChangeAndKeepsItsRecord() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // 진행 중인 변경: 방 상태를 읽은 뒤 커밋 전에 붙잡아 둔다 (같은 잠금 읽기 + 저장을 한 트랜잭션에서).
        CompletableFuture<Void> inFlight = CompletableFuture.runAsync(() ->
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    service.change(room, participant, UNDERSTOOD);
                    holding.countDown();
                    await(release);
                }));
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

        // 종료는 진행 중인 변경이 커밋될 때까지 기다린다.
        CompletableFuture<Void> ending = CompletableFuture.runAsync(() ->
                jdbc.sql("UPDATE lecture_rooms SET status = 'ENDED' WHERE id = ?").param(room).update());
        assertThatThrownBy(() -> ending.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);

        release.countDown();
        inFlight.get(5, TimeUnit.SECONDS);
        ending.get(5, TimeUnit.SECONDS);

        // 종료 전에 시작된 변경은 저장되고, 그 뒤의 변경은 거절된다.
        assertThat(count("understanding_histories")).isEqualTo(1);
        assertThatThrownBy(() -> service.change(room, participant, CONFUSED))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ROOM_ENDED));
        assertThat(count("understanding_histories")).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
