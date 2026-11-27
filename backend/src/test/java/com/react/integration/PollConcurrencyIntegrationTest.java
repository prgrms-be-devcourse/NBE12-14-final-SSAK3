package com.react.integration;

import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.poll.dto.CreatePollRequest;
import com.react.poll.dto.PollOptionResultView;
import com.react.poll.dto.PollView;
import com.react.poll.entity.PollStatus;
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
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class PollConcurrencyIntegrationTest {

    private static final ZoneId SEOUL =
            ZoneId.of("Asia/Seoul");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    PollService pollService;

    @Autowired
    PlatformTransactionManager transactionManager;

    private String hostEmail;
    private long roomId;
    private long participantId;
    private PollView poll;

    @BeforeEach
    void setUp() {
        assertThat(jdbc.sql("SELECT DATABASE()")
                .query(String.class)
                .single())
                .isEqualTo("react_test");

        Flyway.configure()
                .dataSource(dataSource)
                .load()
                .migrate();

        hostEmail = UUID.randomUUID() + "@example.com";

        long hostId = insert("""
                INSERT INTO users
                    (email, password_hash, name, created_at)
                VALUES (?, 'hash', '강연자', ?)
                """,
                hostEmail,
                LocalDateTime.now(SEOUL));

        roomId = insert("""
                INSERT INTO lecture_rooms
                    (host_user_id, title, join_code,
                     status, created_at)
                VALUES (?, '통합 테스트', ?, 'LIVE', ?)
                """,
                hostId,
                UUID.randomUUID().toString()
                        .replace("-", "")
                        .substring(0, 12)
                        .toUpperCase(),
                LocalDateTime.now(SEOUL));

        participantId = insert("""
                INSERT INTO lecture_participants
                    (lecture_room_id, nickname,
                     participant_token_hash, joined_at)
                VALUES (?, '청중', ?, ?)
                """,
                roomId,
                UUID.randomUUID().toString(),
                LocalDateTime.now(SEOUL));

        poll = pollService.create(
                roomId,
                hostEmail,
                new CreatePollRequest(
                        PollType.UNDERSTANDING,
                        null,
                        null,
                        60
                )
        );
    }

    @Test
    void 청중_응답이_먼저_처리되면_마감_결과에_포함된다()
            throws Exception {

        long optionId = poll.options().get(0).optionId();

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        CountDownLatch responseSaved =
                new CountDownLatch(1);

        CountDownLatch allowCommit =
                new CountDownLatch(1);

        CountDownLatch closeStarted =
                new CountDownLatch(1);

        try {
            Future<?> responding = executor.submit(() ->
                    new TransactionTemplate(transactionManager)
                            .executeWithoutResult(status -> {

                                pollService.respond(
                                        roomId,
                                        poll.pollId(),
                                        participantId,
                                        optionId
                                );

                                // 응답은 처리했지만 아직 커밋하지 않는다.
                                responseSaved.countDown();
                                await(allowCommit);
                            })
            );

            assertThat(responseSaved.await(
                    5, TimeUnit.SECONDS))
                    .isTrue();

            Future<?> closing = executor.submit(() -> {
                closeStarted.countDown();

                return pollService.close(
                        roomId,
                        poll.pollId(),
                        hostEmail
                );
            });

            assertThat(closeStarted.await(
                    5, TimeUnit.SECONDS))
                    .isTrue();

            // 먼저 시작된 응답의 트랜잭션이 끝날 때까지
            // 마감 요청은 완료되지 않아야 한다.
            assertThatThrownBy(() ->
                    closing.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            allowCommit.countDown();

            responding.get(10, TimeUnit.SECONDS);
            closing.get(10, TimeUnit.SECONDS);

            var result = pollService.results(
                    roomId,
                    poll.pollId(),
                    hostEmail
            );

            assertThat(result.status())
                    .isEqualTo(PollStatus.CLOSED);

            assertThat(result.totalParticipants())
                    .isEqualTo(1);

            assertThat(result.respondedParticipants())
                    .isEqualTo(1);

            assertThat(result.unrespondedParticipants())
                    .isZero();

            assertThat(result.options().stream()
                    .mapToLong(PollOptionResultView::count)
                    .sum())
                    .isEqualTo(1);

            assertThat(result.options())
                    .filteredOn(option ->
                            option.optionId() == optionId)
                    .singleElement()
                    .satisfies(option ->
                            assertThat(option.count())
                                    .isEqualTo(1));

            assertThat(responseCount())
                    .isEqualTo(1);

        } finally {
            allowCommit.countDown();
            shutdown(executor);
        }
    }

    @Test
    void 강연자_마감이_먼저_처리되면_대기중인_응답은_거절된다()
            throws Exception {

        long optionId = poll.options().get(0).optionId();

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        CountDownLatch pollClosed =
                new CountDownLatch(1);

        CountDownLatch allowCommit =
                new CountDownLatch(1);

        CountDownLatch responseStarted =
                new CountDownLatch(1);

        try {
            Future<?> closing = executor.submit(() ->
                    new TransactionTemplate(transactionManager)
                            .executeWithoutResult(status -> {

                                pollService.close(
                                        roomId,
                                        poll.pollId(),
                                        hostEmail
                                );

                                // 마감 처리 후 커밋을 보류한다.
                                pollClosed.countDown();
                                await(allowCommit);
                            })
            );

            assertThat(pollClosed.await(
                    5, TimeUnit.SECONDS))
                    .isTrue();

            Future<?> responding = executor.submit(() -> {
                responseStarted.countDown();

                return pollService.respond(
                        roomId,
                        poll.pollId(),
                        participantId,
                        optionId
                );
            });

            assertThat(responseStarted.await(
                    5, TimeUnit.SECONDS))
                    .isTrue();

            assertThatThrownBy(() ->
                    responding.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            allowCommit.countDown();

            closing.get(10, TimeUnit.SECONDS);

            assertThatThrownBy(() ->
                    responding.get(10, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(
                            ExecutionException.class,
                            exception -> {
                                assertThat(exception.getCause())
                                        .isInstanceOfSatisfying(
                                                BusinessException.class,
                                                businessException ->
                                                        assertThat(
                                                                businessException
                                                                        .getErrorCode()
                                                        ).isEqualTo(
                                                                ErrorCode.POLL_CLOSED
                                                        )
                                        );
                            });

            var result = pollService.results(
                    roomId,
                    poll.pollId(),
                    hostEmail
            );

            assertThat(result.status())
                    .isEqualTo(PollStatus.CLOSED);

            assertThat(result.totalParticipants())
                    .isEqualTo(1);

            assertThat(result.respondedParticipants())
                    .isZero();

            assertThat(result.unrespondedParticipants())
                    .isEqualTo(1);

            assertThat(result.options().stream()
                    .mapToLong(PollOptionResultView::count)
                    .sum())
                    .isZero();

            assertThat(responseCount())
                    .isZero();

        } finally {
            allowCommit.countDown();
            shutdown(executor);
        }
    }

    private long responseCount() {
        return jdbc.sql("""
                SELECT COUNT(*)
                FROM poll_responses
                WHERE poll_id = ?
                """)
                .param(poll.pollId())
                .query(Long.class)
                .single();
    }

    private long insert(String sql, Object... params) {
        GeneratedKeyHolder keys =
                new GeneratedKeyHolder();

        jdbc.sql(sql)
                .params(params)
                .update(keys);

        return keys.getKey().longValue();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "트랜잭션 대기 시간 초과"
                );
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "테스트 스레드가 중단되었습니다.",
                    e
            );
        }
    }

    private static void shutdown(
            ExecutorService executor
    ) throws InterruptedException {

        executor.shutdown();

        if (!executor.awaitTermination(
                10, TimeUnit.SECONDS)) {

            executor.shutdownNow();

            if (!executor.awaitTermination(
                    10, TimeUnit.SECONDS)) {

                throw new IllegalStateException(
                        "테스트 스레드를 종료하지 못했습니다."
                );
            }
        }
    }
}
