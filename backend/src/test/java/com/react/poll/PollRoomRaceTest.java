package com.react.poll;

import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.poll.dto.CreatePollRequest;
import com.react.poll.entity.PollType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 같은 강연방의 동시 투표 생성과, 강연 종료와 투표 생성·마감의 경합을 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
class PollRoomRaceTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final CreatePollRequest UNDERSTANDING = new CreatePollRequest(PollType.UNDERSTANDING, null, null, 60);

    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired PollService service;
    @Autowired PlatformTransactionManager transactionManager;
    private String hostEmail;
    private long room;

    @BeforeEach
    void prepareRoom() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        hostEmail = UUID.randomUUID() + "@example.com";
        long host = insert("INSERT INTO users(email,password_hash,name,created_at) VALUES (?, 'hash', '강연자', ?)",
                hostEmail, LocalDateTime.now(SEOUL));
        room = insert("""
                INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at)
                VALUES (?, 'test', ?, 'LIVE', ?)
                """, host, UUID.randomUUID().toString(), LocalDateTime.now(SEOUL));
    }

    @Test
    void concurrentCreationsLeaveExactlyOneOpenPoll() throws Exception {
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<ErrorCode>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(CompletableFuture.supplyAsync(() -> {
                ready.countDown();
                await(go);
                try {
                    service.create(room, hostEmail, UNDERSTANDING);
                    return null;
                } catch (BusinessException e) {
                    return e.getErrorCode();
                }
            }, executor));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        long created = 0;
        long rejected = 0;
        for (var result : results) {
            ErrorCode code = result.get(10, TimeUnit.SECONDS);
            if (code == null) {
                created++;
            } else {
                assertThat(code).isEqualTo(ErrorCode.POLL_ALREADY_OPEN);
                rejected++;
            }
        }
        executor.shutdown();

        assertThat(created).isEqualTo(1);
        assertThat(rejected).isEqualTo(threads - 1);
        assertThat(openPolls()).isEqualTo(1);
    }

    @Test
    void createWaitsForUncommittedEndAndIsRejectedAfterIt() throws Exception {
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

        CompletableFuture<Object> creating = CompletableFuture.supplyAsync(() -> service.create(room, hostEmail, UNDERSTANDING));
        // 종료가 커밋되기 전에는 생성이 방 행 잠금을 기다린다.
        assertThatThrownBy(() -> creating.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);

        commitEnd.countDown();
        ending.get(5, TimeUnit.SECONDS);

        assertThatThrownBy(() -> creating.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(BusinessException.class)
                .rootCause().satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.ROOM_ENDED));
        assertThat(count()).isZero();
    }

    @Test
    void endWaitsForInFlightCreationAndKeepsItsPoll() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // 진행 중인 생성: 방 행을 잠그고 투표를 저장한 뒤 커밋 전에 붙잡아 둔다.
        CompletableFuture<Void> inFlight = CompletableFuture.runAsync(() ->
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    service.create(room, hostEmail, UNDERSTANDING);
                    holding.countDown();
                    await(release);
                }));
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

        // 종료는 진행 중인 생성이 커밋될 때까지 기다린다.
        CompletableFuture<Void> ending = CompletableFuture.runAsync(() ->
                jdbc.sql("UPDATE lecture_rooms SET status = 'ENDED' WHERE id = ?").param(room).update());
        assertThatThrownBy(() -> ending.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);

        release.countDown();
        inFlight.get(5, TimeUnit.SECONDS);
        ending.get(5, TimeUnit.SECONDS);

        // 종료 전에 시작된 생성은 저장되고, 그 뒤의 생성은 거절된다.
        assertThat(count()).isEqualTo(1);
        assertThatThrownBy(() -> service.create(room, hostEmail, UNDERSTANDING))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ROOM_ENDED));
        assertThat(count()).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long count() {
        return jdbc.sql("SELECT COUNT(*) FROM polls WHERE lecture_room_id = ?").param(room).query(Long.class).single();
    }

    private long openPolls() {
        return jdbc.sql("SELECT COUNT(*) FROM polls WHERE lecture_room_id = ? AND status = 'OPEN'")
                .param(room).query(Long.class).single();
    }

    private long insert(String sql, Object... params) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql(sql).params(params).update(keys);
        return keys.getKey().longValue();
    }
}
