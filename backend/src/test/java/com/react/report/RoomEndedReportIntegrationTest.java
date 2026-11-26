package com.react.report;

import com.react.report.dto.ReportReceipt;
import com.react.report.repository.ReportRequestRepository;
import com.react.report.service.ReportRecoveryService;
import com.react.report.service.ReportRequestService;
import com.react.room.service.LectureRoomService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoomEndedReportIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired LectureRoomService rooms;
    @Autowired ReportRequestService reportRequests;
    @Autowired ReportRecoveryService recovery;
    @Autowired TransactionTemplate transactions;
    @MockitoSpyBean ReportRequestRepository reports;
    private String owner;
    private long room;

    @BeforeAll
    void migrate() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @BeforeEach
    void liveRoom() {
        String key = UUID.randomUUID().toString();
        owner = key + "@example.com";
        jdbc.sql("INSERT INTO users(email,password_hash,name,created_at) VALUES (?, 'hash','host', NOW())")
                .param(owner).update();
        jdbc.sql("""
                INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at,started_at)
                SELECT id,'test',?,'LIVE',NOW(),UTC_TIMESTAMP() + INTERVAL 9 HOUR - INTERVAL 1 MINUTE
                FROM users WHERE email=?
                """).params(key, owner).update();
        room = jdbc.sql("SELECT id FROM lecture_rooms WHERE join_code=?").param(key).query(Long.class).single();
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM lecture_reports WHERE lecture_room_id=?").param(room).update();
        jdbc.sql("DELETE FROM lecture_rooms WHERE id=?").param(room).update();
        jdbc.sql("DELETE FROM users WHERE email=?").param(owner).update();
    }

    @Test
    void receivesOnlyAfterRoomEndCommits() {
        transactions.executeWithoutResult(tx -> {
            endRoom();
            assertThat(reports.findByRoom(room)).isEmpty();
            tx.setRollbackOnly();
        });
        assertThat(roomStatus()).isEqualTo("LIVE");
        assertThat(reports.findByRoom(room)).isEmpty();

        endRoom();
        assertThat(roomStatus()).isEqualTo("ENDED");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(reports.findByRoom(room)).hasValueSatisfying(receipt ->
                        assertThat(receipt.status()).isEqualTo(ReportReceipt.Status.PENDING)));
    }

    @Test
    void receiptFailureKeepsTheRoomEndedAndRecoveryFindsTheSameReceiptAsManualRequest() throws InterruptedException {
        var allowInsert = new CountDownLatch(1);
        var rolledBack = new CountDownLatch(1);
        doAnswer(call -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK && allowInsert.getCount() == 0) {
                        rolledBack.countDown();
                    }
                }
            });
            if (!allowInsert.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("방 종료 응답이 보고서 저장을 기다렸습니다.");
            }
            throw new DataAccessResourceFailureException("simulated insert failure");
        }).doCallRealMethod().when(reports).insertPending(room);

        try {
            endRoom();
            assertThat(roomStatus()).isEqualTo("ENDED");
        } finally {
            allowInsert.countDown();
        }
        assertThat(rolledBack.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(reports.findByRoom(room)).isEmpty();
        assertThat(receiptDue()).isTrue();
        recovery.recover();
        assertThat(receiptDue()).isFalse();
        var receipt = reports.findByRoom(room).orElseThrow();
        assertThat(receipt.status()).isEqualTo(ReportReceipt.Status.PENDING);
        assertThat(reportRequests.request(room, owner)).isEqualTo(receipt);
    }

    private boolean receiptDue() {
        return jdbc.sql("SELECT report_receipt_due FROM lecture_rooms WHERE id=?").param(room).query(Boolean.class).single();
    }

    private void endRoom() {
        rooms.end(room, new TestingAuthenticationToken(owner, null, "ROLE_HOST"));
    }

    private String roomStatus() {
        return jdbc.sql("SELECT status FROM lecture_rooms WHERE id=?").param(room).query(String.class).single();
    }
}
