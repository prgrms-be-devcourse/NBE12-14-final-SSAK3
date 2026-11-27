package com.react.integration;

import com.react.auth.entity.User;
import com.react.auth.repository.UserRepository;
import com.react.poll.dto.CreatePollRequest;
import com.react.poll.dto.PollCloseView;
import com.react.poll.dto.PollResultView;
import com.react.poll.dto.PollView;
import com.react.poll.entity.PollStatus;
import com.react.poll.entity.PollType;
import com.react.poll.service.PollService;
import com.react.room.entity.LectureRoom;
import com.react.room.repository.LectureRoomRepository;
import com.react.room.service.LectureParticipantService;
import com.react.room.service.ParticipantJoinWriteService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class PollParticipantIntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    UserRepository users;

    @Autowired
    LectureRoomRepository rooms;

    @Autowired
    PollService pollService;

    @Autowired
    LectureParticipantService participants;

    @Autowired
    ParticipantJoinWriteService joinWriteService;

    @Autowired
    PlatformTransactionManager transactionManager;

    private String hostEmail;
    private String joinCode;
    private long roomId;
    private PollView poll;

    @BeforeEach
    void setUp() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).isEqualTo("react_test");
        Flyway.configure().dataSource(dataSource).load().migrate();

        hostEmail = UUID.randomUUID() + "@example.com";
        User host = users.saveAndFlush(new User(hostEmail, "hash", "강연자"));

        joinCode = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();

        LectureRoom room = rooms.saveAndFlush(new LectureRoom(
                host, "참여자 마감 동시성 테스트", "SCRUM-172", joinCode));

        roomId = room.getId();

        jdbc.sql("""
                UPDATE lecture_rooms
                SET status = 'LIVE', started_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """).param(roomId).update();

        LectureParticipantService.JoinResult first = join("기존 청중");

        assertThat(first.created()).isTrue();

        poll = pollService.create(roomId, hostEmail,
                new CreatePollRequest(PollType.UNDERSTANDING, null, null, 60));

        pollService.respond(roomId, poll.pollId(), first.response().participantId(),
                poll.options().get(0).optionId());

        assertThat(participantCount()).isEqualTo(1);
    }

    @Test
    void 신규_청중_입장이_먼저_처리되면_마감_집계에_포함된다() throws Exception {
        CountDownLatch participantSaved = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);

        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            try {
                Future<?> joining = executor.submit(() ->
                        transactions.executeWithoutResult(status -> {
                            String tokenHash = UUID.randomUUID().toString().replace("-", "").repeat(2);

                            ParticipantJoinWriteService.SaveResult saved =
                                    joinWriteService.saveIfAbsent(roomId, "신규 청중", tokenHash);

                            assertThat(saved.created()).isTrue();

                            participantSaved.countDown();
                            await(allowCommit);
                        }));

                assertThat(participantSaved.await(5, TimeUnit.SECONDS)).isTrue();

                Future<PollCloseView> closing = executor.submit(() -> {
                    closeStarted.countDown();
                    return pollService.close(roomId, poll.pollId(), hostEmail);
                });

                assertThat(closeStarted.await(5, TimeUnit.SECONDS)).isTrue();

                // 청중 입장 트랜잭션이 커밋될 때까지 마감은 기다려야 한다.
                assertThatThrownBy(() -> closing.get(500, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);

                allowCommit.countDown();

                joining.get(10, TimeUnit.SECONDS);
                closing.get(10, TimeUnit.SECONDS);

                assertThat(participantCount()).isEqualTo(2);
                assertClosedResult(2, 1, 1);
            } finally {
                allowCommit.countDown();
            }
        }
    }

    @Test
    void 이해도_마감이_먼저_처리되면_신규_청중은_기존_집계에_포함되지_않는다() throws Exception {
        CountDownLatch pollClosed = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CountDownLatch joinStarted = new CountDownLatch(1);

        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            try {
                Future<?> closing = executor.submit(() ->
                        transactions.executeWithoutResult(status -> {
                            pollService.close(roomId, poll.pollId(), hostEmail);

                            pollClosed.countDown();
                            await(allowCommit);
                        }));

                assertThat(pollClosed.await(5, TimeUnit.SECONDS)).isTrue();

                Future<LectureParticipantService.JoinResult> joining = executor.submit(() -> {
                    joinStarted.countDown();
                    return join("마감과 동시에 입장한 청중");
                });

                assertThat(joinStarted.await(5, TimeUnit.SECONDS)).isTrue();

                // 마감 트랜잭션이 커밋될 때까지 신규 입장 저장은 기다려야 한다.
                assertThatThrownBy(() -> joining.get(500, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);

                allowCommit.countDown();

                closing.get(10, TimeUnit.SECONDS);

                LectureParticipantService.JoinResult joined = joining.get(10, TimeUnit.SECONDS);

                assertThat(joined.created()).isTrue();
                assertThat(participantCount()).isEqualTo(2);

                // 강연방에는 2명이 있지만 마감된 이해도 집계에는 1명만 포함된다.
                assertClosedResult(1, 1, 0);
            } finally {
                allowCommit.countDown();
            }
        }
    }

    private LectureParticipantService.JoinResult join(String nickname) {
        return participants.join(joinCode, nickname, UUID.randomUUID().toString(), Map.of());
    }

    private void assertClosedResult(long total, long responded, long unresponded) {
        PollResultView result = pollService.results(roomId, poll.pollId(), hostEmail);

        assertThat(result.status()).isEqualTo(PollStatus.CLOSED);
        assertThat(result.totalParticipants()).isEqualTo(total);
        assertThat(result.respondedParticipants()).isEqualTo(responded);
        assertThat(result.unrespondedParticipants()).isEqualTo(unresponded);
        assertThat(result.options().get(0).count()).isEqualTo(1);

        long savedTotal = jdbc.sql("SELECT closed_total_participants FROM polls WHERE id = ?")
                .param(poll.pollId()).query(Long.class).single();

        assertThat(savedTotal).isEqualTo(total);

        long responses = jdbc.sql("SELECT COUNT(*) FROM poll_responses WHERE poll_id = ?")
                .param(poll.pollId()).query(Long.class).single();

        assertThat(responses).isEqualTo(responded);
    }

    private long participantCount() {
        return jdbc.sql("SELECT COUNT(*) FROM lecture_participants WHERE lecture_room_id = ?")
                .param(roomId).query(Long.class).single();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("트랜잭션 대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("테스트 스레드 중단", e);
        }
    }
}