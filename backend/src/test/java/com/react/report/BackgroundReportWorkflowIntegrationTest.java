package com.react.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.report.dto.ReportDetailView;
import com.react.report.dto.ReportReceipt;
import com.react.report.dto.ReportStatusView;
import com.react.report.repository.ReportAttemptRepository;
import com.react.report.service.BackgroundReportClient;
import com.react.report.service.ReportGenerationException;
import com.react.report.service.ReportGenerationWorker;
import com.react.report.service.ReportQueryService;
import com.react.report.service.ReportRecoveryService;
import com.react.report.service.ReportRequestService;
import com.react.report.service.ReportResponseRecovery;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"app.ai.report.background-enabled=true",
        "app.ai.report.webhook-secret=whsec_MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackgroundReportWorkflowIntegrationTest {
    private static final String EMAIL = "background-test@example.com";
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 22, 10, 0);
    private static final String ORIGINAL = "처음 저장한 질문입니다.";
    @Autowired DataSource dataSource;
    @Autowired DataSourceProperties database;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PasswordEncoder passwords;
    @Autowired ReportRequestService requests;
    @Autowired ReportQueryService query;
    @Autowired ReportGenerationWorker worker;
    @Autowired ReportRecoveryService restart;
    @Autowired ReportResponseRecovery recovery;
    @Autowired ReportAttemptRepository attempts;
    @MockitoBean BackgroundReportClient provider;
    @MockitoBean(name = "reportGenerationExecutor") Executor executor;
    private Flyway flyway;
    private long room;

    @BeforeAll
    void migrateTestDatabase() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).isEqualTo("react_test");
        flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
    }

    @AfterAll
    void cleanTestDatabase() {
        if (flyway != null) flyway.clean();
    }

    @BeforeEach
    void finalizedRoom() throws Exception {
        jdbc.sql("DELETE FROM report_webhook_inbox").update();
        jdbc.sql("DELETE FROM lecture_reports").update();
        jdbc.sql("""
                INSERT IGNORE INTO users(email,password_hash,name,created_at) VALUES (?,'hash','강연자',?)
                """).params(EMAIL, START).update();
        room = insert("""
                INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at,started_at,ended_at)
                SELECT id,'복구 검증',?,'ENDED',?,?,? FROM users WHERE email=?
                """, UUID.randomUUID().toString(), START, START, START.plusSeconds(15), EMAIL);
        long participant = insert("""
                INSERT INTO lecture_participants(lecture_room_id,nickname,participant_token_hash,joined_at)
                VALUES (?,'청중',?,?)
                """, room, UUID.randomUUID().toString(), START);
        jdbc.sql("INSERT INTO chat_messages(lecture_room_id,participant_id,content,received_at) VALUES (?,?,?,?)")
                .params(room, participant, ORIGINAL, START.plusSeconds(1)).update();
        jdbc.sql("""
                INSERT INTO ai_analyses(lecture_room_id,window_start_at,window_end_at,status,created_at)
                VALUES (?,?,?,'COMPLETED',?)
                """).params(room, START, START.plusSeconds(15), START).update();
        jdbc.sql("""
                INSERT INTO ai_analysis_finalizations(lecture_room_id,started_at,ended_at,status,created_at,updated_at)
                VALUES (?,?,?,'COMPLETED',?,?)
                """).params(room, START, START.plusSeconds(15), START, START.plusSeconds(15)).update();
        var draft = mapper.readTree("""
                {"overview":{"mainInterests":[],"reviewPoints":[],"suggestions":[]},
                 "followUp":{"reviewedChatIds":[],"groups":[]}}
                """);
        when(provider.draft(any())).thenReturn(draft);
    }

    @Test
    void 저장된_ID만으로_재시작_후_당시_입력으로_복구하고_삭제_실패도_재처리한다() {
        String id = "resp_known";
        queueSubmission(id);
        var receipt = requests.request(room, EMAIL);
        worker.check(room);
        assertThat(query.status(room, EMAIL).status()).isEqualTo(ReportReceipt.Status.PROCESSING);
        jdbc.sql("UPDATE chat_messages SET content='전송 이후 수정' WHERE lecture_room_id=?").param(room).update();

        restart.recover();
        when(provider.retrieve(id)).thenReturn(response(id, "completed", attemptKey(receipt, false)));
        recovery.pollResponses();
        var saved = query.detail(room, EMAIL);
        assertThat(saved.status()).isEqualTo(ReportReceipt.Status.COMPLETED);
        assertThat(saved.content().at("/timeline/0/chatMessages/0/text").asText()).isEqualTo(ORIGINAL);
        verify(provider, times(1)).submit(any(), anyString());

        doThrow(ReportGenerationException.temporaryFailure()).doThrow(new BackgroundReportClient.NotFound())
                .when(provider).delete(id);
        recovery.deleteResponses();
        assertThat(state(id)).isEqualTo("DELETE_PENDING");
        jdbc.sql("UPDATE report_generation_attempts SET next_action_at=UTC_TIMESTAMP(6) WHERE response_id=?").param(id).update();
        recovery.deleteResponses();
        assertThat(state(id)).isEqualTo("FINISHED");
        assertThat(query.detail(room, EMAIL)).isEqualTo(saved);
    }

    @Test
    void 응답_ID_유실후_바로_재접수하고_늦은_완료가_먼저면_추가_POST를_생략한다() {
        var receipt = unknownFirstAttempt();
        var retried = requests.retry(room, EMAIL);
        assertThat(retried.reportId()).isEqualTo(receipt.reportId());
        assertThat(retried.status()).isEqualTo(ReportReceipt.Status.PENDING);
        String id = "resp_late";
        when(provider.retrieve(id)).thenReturn(response(id, "completed", attemptKey(receipt, false)));
        attempts.enqueueWebhook(id);
        recovery.receiveWebhooks();
        worker.check(room);
        assertThat(query.detail(room, EMAIL).status()).isEqualTo(ReportReceipt.Status.COMPLETED);
        assertThat(query.status(room, EMAIL).retryAvailable()).isFalse();
        verify(provider).submit(any(), anyString());
        recovery.deleteResponses();
        verify(provider).delete(id);
    }

    @Test
    void 재시도_완료후_유실됐던_원본과_다른_서비스_알림이_도착해도_저장한_결과를_보존한다() {
        var receipt = unknownFirstAttempt();
        requests.retry(room, EMAIL);
        String retryId = "resp_retry";
        queueSubmission(retryId);
        worker.check(room);
        when(provider.retrieve(retryId)).thenReturn(response(retryId, "completed", attemptKey(receipt, true)));
        recovery.pollResponses();
        var winner = query.detail(room, EMAIL);
        assertThat(winner.status()).isEqualTo(ReportReceipt.Status.COMPLETED);

        String original = "resp_original";
        when(provider.retrieve(original)).thenReturn(response(original, "completed", attemptKey(receipt, false)));
        String foreign = "resp_foreign";
        when(provider.retrieve(foreign)).thenReturn(response(foreign, "completed", UUID.randomUUID().toString()));
        attempts.enqueueWebhook(original);
        attempts.enqueueWebhook(foreign);
        recovery.receiveWebhooks();
        assertThat(query.detail(room, EMAIL)).isEqualTo(winner);
        recovery.deleteResponses();
        verify(provider).delete(original);
        verify(provider).delete(retryId);
        verify(provider, never()).delete(foreign);
    }

    @Test
    void 청중_자료가_없으면_외부_요청없이_보고서를_완성한다() {
        jdbc.sql("DELETE FROM chat_messages WHERE lecture_room_id=?").param(room).update();
        var receipt = requests.request(room, EMAIL);
        worker.check(room);
        assertThat(query.detail(room, EMAIL).status()).isEqualTo(ReportReceipt.Status.COMPLETED);
        assertThat(attempts.hasAttempt(receipt.reportId())).isFalse();
        verify(provider, never()).submit(any(), anyString());
    }

    @Test
    void 저장된_ID라도_나중에_다른_시도라고_조회되면_응답을_노출하거나_삭제하지_않는다() {
        String id = "resp_changed";
        queueSubmission(id);
        requests.request(room, EMAIL);
        worker.check(room);
        when(provider.retrieve(id)).thenReturn(response(id, "completed", UUID.randomUUID().toString()));
        recovery.pollResponses();
        var status = query.status(room, EMAIL);
        assertThat(status.failureCategory()).isEqualTo(ReportStatusView.FailureCategory.UNCONFIRMED);
        assertThat(status.retryAvailable()).isTrue();
        assertThat(query.detail(room, EMAIL).content()).isNull();
        recovery.deleteResponses();
        verify(provider, never()).delete(id);
    }

    @Test
    void 최종_분석이_확정됐지만_필수_입력이_누락된_경우_무한대기하지_않는다() {
        jdbc.sql("DELETE FROM ai_analyses WHERE lecture_room_id=?").param(room).update();
        requests.request(room, EMAIL);
        worker.check(room);
        assertThat(query.status(room, EMAIL).status()).isEqualTo(ReportReceipt.Status.FAILED);
        verify(provider, never()).submit(any(), anyString());
    }

    private ReportReceipt unknownFirstAttempt() {
        when(provider.submit(any(), anyString())).thenThrow(
                new BackgroundReportClient.SubmissionUnknown(ReportGenerationException.temporaryFailure()));
        var receipt = requests.request(room, EMAIL);
        worker.check(room);
        var status = query.status(room, EMAIL);
        assertThat(status.failureCategory()).isEqualTo(ReportStatusView.FailureCategory.UNCONFIRMED);
        assertThat(status.retryAvailable()).isTrue();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM report_generation_slots WHERE attempt_key IS NOT NULL")
                .query(Integer.class).single()).isZero();
        return receipt;
    }

    private void queueSubmission(String id) {
        doAnswer(call -> response(id, "queued", call.getArgument(1, String.class)))
                .when(provider).submit(any(), anyString());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_REPORT_RESTART_TEST", matches = "true")
    void recoversAfterBackendProcessIsKilled(@TempDir Path directory) throws Exception {
        try (var provider = new ReportRecoveryTestProvider()) {
            long reportId = requests.request(room, EMAIL).reportId();
            int port = provider.origin().getPort();
            String responseId = killBackendAfterIdIsStored(directory.resolve("first-backend.log"), port, reportId);
            assertThat(responseId).isEqualTo(ReportRecoveryTestProvider.RESPONSE_ID);
            assertThat(provider.createCount()).isOne();
            assertThat(provider.completedReadCount()).isZero();

            provider.complete();
            assertThat(query.detail(room, EMAIL).content()).as("백엔드가 꺼진 동안의 로컬 본문").isNull();
            var result = recoverOnNewBackend(directory.resolve("restarted-backend.log"), port, reportId, responseId);
            assertThat(result.content().at("/overview/mainInterests/0/text").asText()).isEqualTo(ReportRecoveryTestProvider.MARKER);
            assertThat(provider.completedReadCount()).isPositive();
            assertThat(provider.createCount()).as("재시작 후에도 OpenAI 생성 POST는 한 번").isOne();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_REPORT_RESTART_TEST", matches = "true")
    void recoversAfterBackendIsKilledBeforeResponseIdIsSaved(@TempDir Path directory) throws Exception {
        String password = "restart-test-only";
        jdbc.sql("UPDATE users SET password_hash=? WHERE email=?").params(passwords.encode(password), EMAIL).update();
        long reportId = requests.request(room, EMAIL).reportId();
        try (var provider = new ReportRecoveryTestProvider()) {
            int port = provider.origin().getPort();
            Path firstLog = directory.resolve("before-id-save.log");
            Process first = startBackend(firstLog, port, true);
            try {
                assertThat(first.waitFor(90, TimeUnit.SECONDS)).as("응답 ID 저장 직전에 첫 JVM 종료").isTrue();
                assertThat(first.exitValue()).as("ID 저장 경계에서 강제 종료한 JVM")
                        .isEqualTo(ReportRecoveryTestProcess.HALTED_BEFORE_ID_SAVE);
            } catch (AssertionError failure) {
                failure.addSuppressed(new AssertionError(logTail(firstLog)));
                throw failure;
            } finally {
                if (first.isAlive()) stopBackend(first);
            }
            assertThat(provider.createCount()).isOne();
            assertThat(jdbc.sql("""
                    SELECT COUNT(*) FROM report_generation_attempts a
                    JOIN report_generation_slots s ON s.attempt_key=a.attempt_key
                    WHERE a.report_id=? AND a.state='SUBMITTING' AND a.response_id IS NULL AND a.retry_used=FALSE
                    """).param(reportId).query(Integer.class).single()).isOne();

            Path log = directory.resolve("retry-backend.log");
            Process second = startBackend(log, port, false);
            try {
                Path portFile = ReportRecoveryTestProcess.portFile(log);
                await().alias("새 백엔드 기동").atMost(Duration.ofSeconds(90))
                        .failFast("재시작한 JVM이 종료됨", () -> !second.isAlive()).until(() -> Files.exists(portFile));
                int backendPort = Integer.parseInt(Files.readString(portFile));
                try (var host = ReportRecoveryTestHost.login(backendPort, EMAIL, password, mapper)) {
                    verifyImmediateRetry(host, provider, reportId);
                }
            } catch (Exception | AssertionError failure) {
                failure.addSuppressed(new AssertionError(logTail(log)));
                throw failure;
            } finally {
                stopBackend(second);
            }
        }
    }

    private void verifyImmediateRetry(ReportRecoveryTestHost host, ReportRecoveryTestProvider provider, long reportId)
            throws Exception {
        // Only allow for the startup recovery pass. The provider never sends a webhook.
        await().alias("복구 직후 강사 화면에 재시도 허용").atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var status = host.status(room);
            assertThat(status.required("failureCategory").asText()).isEqualTo("UNCONFIRMED");
            assertThat(status.required("retryAvailable").booleanValue()).isTrue();
        });
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM report_generation_slots s
                JOIN report_generation_attempts a ON a.attempt_key=s.attempt_key WHERE a.report_id=?
                """).param(reportId).query(Integer.class).single()).isZero();
        assertThat(provider.createCount()).as("수동 재시도 전까지 최초 요청 하나").isOne();

        var receipt = host.retry(room, 202);
        assertThat(receipt.required("reportId").asLong()).isEqualTo(reportId);
        assertThat(receipt.required("status").asText()).isEqualTo("PENDING");
        assertThat(jdbc.sql("SELECT retry_used FROM lecture_reports WHERE id=?")
                .param(reportId).query(Boolean.class).single()).isTrue();
        assertThat(host.status(room).required("retryAvailable").booleanValue()).isFalse();
        assertThat(host.retry(room, 202).required("reportId").asLong()).isEqualTo(reportId);

        provider.complete();
        await().alias("강사의 재시도로 생성한 본문 저장").atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            var saved = query.detail(room, EMAIL);
            assertThat(saved.reportId()).isEqualTo(reportId);
            assertThat(saved.status()).isEqualTo(ReportReceipt.Status.COMPLETED);
            assertThat(saved.content().at("/overview/mainInterests/0/text").asText())
                    .isEqualTo(ReportRecoveryTestProvider.RETRY_MARKER);
        });
        assertThat(jdbc.sql("""
                SELECT response_id FROM report_generation_attempts WHERE report_id=? ORDER BY retry_used
                """).param(reportId).query(String.class).list())
                .containsExactly(null, ReportRecoveryTestProvider.RETRY_RESPONSE_ID);
        assertThat(provider.createCount()).as("최초 요청과 강사 재시도 각 한 건").isEqualTo(2);
    }

    private String killBackendAfterIdIsStored(Path log, int providerPort, long reportId) throws Exception {
        Process first = startBackend(log, providerPort, false);
        try {
            await().alias("첫 JVM이 응답 ID를 MySQL에 커밋").atMost(Duration.ofSeconds(90))
                    .failFast("첫 JVM이 시작 도중 종료됨", () -> !first.isAlive()).until(() ->
                        jdbc.sql("""
                                SELECT COUNT(*) FROM report_generation_attempts a JOIN lecture_reports r ON r.id=a.report_id
                                WHERE r.id=? AND r.status='PROCESSING' AND r.content IS NULL AND a.state='REMOTE'
                                  AND a.response_id IS NOT NULL AND a.retry_used=FALSE
                                """).param(reportId).query(Integer.class).single() == 1);
            return jdbc.sql("SELECT response_id FROM report_generation_attempts WHERE report_id=?")
                    .param(reportId).query(String.class).single();
        } catch (RuntimeException | AssertionError failure) {
            failure.addSuppressed(new AssertionError(logTail(log)));
            throw failure;
        } finally {
            stopBackend(first);
        }
    }

    private ReportDetailView recoverOnNewBackend(Path log, int providerPort, long reportId, String responseId)
            throws Exception {
        Process second = startBackend(log, providerPort, false);
        try {
            // A previous poll may have claimed a 30-second lease. Let the normal scheduler reclaim it.
            await().alias("두 번째 JVM이 기존 응답을 복구하고 외부 응답 삭제까지 확인")
                    .atMost(Duration.ofSeconds(120)).failFast("재시작한 JVM이 종료됨", () -> !second.isAlive())
                    .until(() -> "FINISHED".equals(state(responseId)));
            var saved = query.detail(room, EMAIL);
            assertThat(saved.reportId()).isEqualTo(reportId);
            assertThat(saved.status()).isEqualTo(ReportReceipt.Status.COMPLETED);
            assertThat(saved.content().at("/timeline/0/chatMessages/0/text").asText()).isEqualTo(ORIGINAL);
            assertThat(jdbc.sql("SELECT response_id FROM report_generation_attempts WHERE report_id=?")
                    .param(reportId).query(String.class).list()).containsExactly(responseId);
            assertThat(jdbc.sql("SELECT retry_used FROM lecture_reports WHERE id=?")
                    .param(reportId).query(Boolean.class).single()).isFalse();
            return saved;
        } catch (RuntimeException | AssertionError failure) {
            failure.addSuppressed(new AssertionError(logTail(log)));
            throw failure;
        } finally {
            stopBackend(second);
        }
    }

    private Process startBackend(Path log, int providerPort, boolean haltBeforeIdSave) throws IOException {
        return ReportRecoveryTestProcess.start(log, database.determineUrl(), database.determineUsername(),
                database.determinePassword(), providerPort, haltBeforeIdSave);
    }

    private static void stopBackend(Process child) throws InterruptedException {
        child.destroyForcibly();
        assertThat(child.waitFor(15, TimeUnit.SECONDS)).as("테스트 전용 백엔드 종료").isTrue();
    }

    private static String logTail(Path log) throws IOException {
        var lines = Files.readAllLines(log);
        return String.join(System.lineSeparator(), lines.subList(Math.max(0, lines.size() - 35), lines.size()));
    }

    private JsonNode response(String id, String status, String key) {
        var result = mapper.createObjectNode().put("id", id).put("status", status);
        result.putObject("metadata").put("report_attempt", key);
        return result;
    }

    private String attemptKey(ReportReceipt receipt, boolean retry) {
        return jdbc.sql("SELECT attempt_key FROM report_generation_attempts WHERE report_id=? AND retry_used=?")
                .params(receipt.reportId(), retry).query(String.class).single();
    }

    private String state(String id) {
        return jdbc.sql("SELECT state FROM report_generation_attempts WHERE response_id=?")
                .param(id).query(String.class).single();
    }

    private long insert(String sql, Object... params) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql(sql).params(params).update(keys);
        return keys.getKey().longValue();
    }
}
