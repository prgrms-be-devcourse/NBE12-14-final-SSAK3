package com.react.room;

import com.react.auth.entity.User;
import com.react.auth.repository.UserRepository;
import com.react.room.entity.LectureRoom;
import com.react.room.repository.LectureRoomRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LectureParticipantRoomEndConcurrencyIntegrationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    UserRepository users;

    @Autowired
    LectureRoomRepository rooms;

    @Autowired
    PlatformTransactionManager transactionManager;

    private Long roomId;
    private String joinCode;

    @BeforeEach
    void setUp() {
        assertThat(
                jdbc.sql("SELECT DATABASE()")
                        .query(String.class)
                        .single()
        ).endsWith("_test");

        Flyway.configure()
                .dataSource(dataSource)
                .load()
                .migrate();

        User host = users.saveAndFlush(
                new User(
                        UUID.randomUUID() + "@example.com",
                        "hash",
                        "강연자"
                )
        );

        joinCode = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 12)
                .toUpperCase();

        LectureRoom room = rooms.saveAndFlush(
                new LectureRoom(
                        host,
                        "동시성 테스트",
                        "강연 종료와 청중 입장 경합 확인",
                        joinCode
                )
        );

        roomId = room.getId();

        // 실제 강연 종료 직전과 동일하게 LIVE 상태 준비
        jdbc.sql("""
                UPDATE lecture_rooms
                SET status = 'LIVE',
                    started_at = ?
                WHERE id = ?
                """)
                .params(LocalDateTime.now(SEOUL), roomId)
                .update();
    }

    @Test
    void 강연종료가_먼저_잠금을_얻으면_신규청중은_입장할수없다()
            throws Exception {

        CountDownLatch endLocked = new CountDownLatch(1);
        CountDownLatch releaseEnd = new CountDownLatch(1);
        CountDownLatch joinStarted = new CountDownLatch(1);

        TransactionTemplate transactions =
                new TransactionTemplate(transactionManager);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {

            // 첫 번째 스레드: 방 종료 트랜잭션
            Future<?> endFuture = executor.submit(() -> {

                transactions.executeWithoutResult(status -> {

                    // 방 행을 수정하여 종료 트랜잭션의
                    // 배타적 잠금을 획득한다.
                    jdbc.sql("""
                            UPDATE lecture_rooms
                            SET status = 'ENDED',
                                ended_at = ?
                            WHERE id = ?
                            """)
                            .params(
                                    LocalDateTime.now(SEOUL),
                                    roomId
                            )
                            .update();

                    endLocked.countDown();

                    try {
                        if (!releaseEnd.await(
                                10,
                                TimeUnit.SECONDS
                        )) {
                            throw new IllegalStateException(
                                    "종료 트랜잭션 해제 시간 초과"
                            );
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                });
            });

            try {
                // 종료 요청이 방 행 잠금을 먼저 획득할 때까지 대기
                assertThat(endLocked.await(5, TimeUnit.SECONDS)).isTrue();

                // 두 번째 스레드: 같은 방에 신규 청중 입장 요청
                Future<MvcResult> joinFuture = executor.submit(() -> {

                    joinStarted.countDown();

                    return mvc.perform(
                            post(
                                    "/api/v1/public/rooms/{joinCode}/participants",
                                    joinCode
                            )
                                    .with(csrf())
                                    .header(
                                            "Idempotency-Key",
                                            UUID.randomUUID().toString()
                                    )
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {"nickname":"동시입장청중"}
                                            """)
                    ).andReturn();
                });

                assertThat(joinStarted.await(5, TimeUnit.SECONDS)).isTrue();

                // 종료 트랜잭션이 잠금을 유지하는 동안
                // 신규 참여자 저장 요청은 완료될 수 없어야 한다.
                assertThatThrownBy(() ->
                        joinFuture.get(
                                300,
                                TimeUnit.MILLISECONDS
                        )
                ).isInstanceOf(TimeoutException.class);

                // 종료 커밋을 허용한다.
                releaseEnd.countDown();
                endFuture.get(10, TimeUnit.SECONDS);

                // 잠금 해제 후 청중 요청 결과 확인
                MvcResult result = joinFuture.get(
                        10,
                        TimeUnit.SECONDS
                );

                assertThat(result.getResponse().getStatus()).isEqualTo(409);
                assertThat(result.getResponse().getContentAsString()).contains("ROOM_ENDED");

                // 종료된 방에 신규 참여자가 저장되지 않아야 한다.
                Long participantCount = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM lecture_participants
                        WHERE lecture_room_id = ?
                        """)
                        .param(roomId)
                        .query(Long.class)
                        .single();

                assertThat(participantCount).isZero();
                assertThat(
                        jdbc.sql("""
                                SELECT status
                                FROM lecture_rooms
                                WHERE id = ?
                                """)
                                .param(roomId)
                                .query(String.class)
                                .single()
                ).isEqualTo("ENDED");

            } finally {
                releaseEnd.countDown();
            }
        }
    }
}
