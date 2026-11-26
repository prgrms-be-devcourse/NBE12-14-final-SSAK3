package com.react.report;

import com.react.report.repository.ReportAttemptRepository;
import com.react.report.repository.ReportAttemptRepository.Attempt;
import com.react.report.repository.ReportGenerationRepository;
import com.react.report.repository.ReportRequestRepository;
import com.react.report.dto.ReportStatusView.FailureCategory;
import com.react.report.service.ReportQueryService;
import com.react.room.repository.RoomHistoryRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportAttemptRepositoryIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired JdbcClient jdbc;
    @Autowired ReportAttemptRepository attempts;
    @Autowired ReportGenerationRepository reports;
    @Autowired ReportRequestRepository requests;
    @Autowired ReportQueryService queries;
    @Autowired RoomHistoryRepository history;
    Flyway flyway;
    long host;

    record Report(long id, long room) {}

    @BeforeAll
    void migrateTestDatabase() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).isEqualTo("react_test");
        flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        host = insert("INSERT INTO users(email,password_hash,name,created_at) VALUES('attempt@test.local','hash','test',NOW())");
    }

    @AfterEach
    void releaseSlotsAndInbox() {
        jdbc.sql("DELETE FROM lecture_reports").update();
        jdbc.sql("DELETE FROM report_webhook_inbox").update();
        assertThat(activeSlots()).isZero();
    }

    @AfterAll
    void clean() {
        if (flyway != null) flyway.clean();
    }

    @Test
    void 여러_인스턴스가_예약해도_두_슬롯만_사용하고_나머지는_PENDING에_남는다() throws Exception {
        var rows = List.of(report(), report(), report());
        var start = new CyclicBarrier(3);
        var futures = new ArrayList<Future<Optional<Attempt>>>();
        try (var pool = Executors.newFixedThreadPool(3)) {
            for (var row : rows) futures.add(pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return attempts.reserve(row.id(), "{}");
            }));
            var accepted = new ArrayList<Attempt>();
            for (var future : futures) future.get(10, TimeUnit.SECONDS).ifPresent(accepted::add);
            assertThat(accepted).hasSize(2);
            assertThat(activeSlots()).isEqualTo(2);
            var waiting = rows.stream().filter(r -> status(r).equals("PENDING")).toList();
            assertThat(waiting).hasSize(1);
            attempts.fail(accepted.getFirst().attemptKey(), "LLM_REQUEST_FAILED", null);
            assertThat(attempts.reserve(waiting.getFirst().id(), "{}")).isPresent();
        }
    }

    @Test
    void crash_복구는_살아있는_전송과_ID가_기록된_작업을_보호하고_나머지만_정리한다() {
        var ambiguous = report();
        var accepted = report();
        var known = reserve(accepted);
        attach(known, "resp_known");
        var legacy = report();
        jdbc.sql("UPDATE lecture_reports SET status='PROCESSING' WHERE id=?").param(legacy.id()).update();
        reports.withPendingLock(ambiguous.id(), () -> {
            reserve(ambiguous);
            reports.failAbandonedJobs(10);
            assertThat(status(ambiguous)).isEqualTo("PROCESSING");
            assertThat(activeSlots()).isEqualTo(2);
        });
        reports.failAbandonedJobs(10);
        reports.failAbandonedJobs(10);
        assertThat(error(ambiguous)).isEqualTo("REPORT_DISPATCH_UNKNOWN");
        assertThat(error(legacy)).isEqualTo("GENERATION_INTERRUPTED");
        assertThat(status(accepted)).isEqualTo("PROCESSING");
        assertThat(activeSlots()).isOne();
        assertThat(attempts.dueResponses(10)).containsExactly("resp_known");
        assertThat(attempts.dueResponses(10)).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM report_alert_outbox WHERE report_id=?")
                .param(ambiguous.id()).query(Integer.class).single()).isOne();
    }

    @Test
    void 원본_검증_결과가_대기나_실패_중_도착해도_완료하고_새_전송은_예약하지_않는다() {
        for (boolean requestRetry : List.of(false, true)) {
            var report = report();
            var first = reserve(report);
            attempts.unknown(first.attemptKey());
            if (requestRetry) assertThat(requests.retryEligibleReport(report.room())).isTrue();
            var attached = attach(first, "resp_" + report.id());
            assertThat(attached.inputJson()).contains("original");
            assertThat(attempts.complete(attached, "{\"winner\":\"original\"}")).isTrue();
            assertThat(status(report)).isEqualTo("COMPLETED");
            assertThat(requests.retryEligibleReport(report.room())).isFalse();
            assertThat(attempts.reserve(report.id(), "{}")).isEmpty();
            assertThat(activeSlots()).isZero();
        }
    }

    @Test
    void 첫_시도와_재시도의_동시_완료는_첫_검증_본문만_확정하고_둘_모두_삭제_대기한다() throws Exception {
        var report = report();
        var first = reserve(report);
        attempts.unknown(first.attemptKey());
        assertThat(requests.retryEligibleReport(report.room())).isTrue();
        var retry = reserve(report);
        var retryResponse = attach(retry, "resp_retry");
        var firstResponse = attach(first, "resp_first");
        assertThat(activeSlots()).isOne();
        var start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var original = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return attempts.complete(firstResponse, "{\"winner\":\"original\"}");
            });
            var second = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return attempts.complete(retryResponse, "{\"winner\":\"retry\"}");
            });
            boolean originalWon = original.get(10, TimeUnit.SECONDS);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(!originalWon);
            assertThat(jdbc.sql("SELECT content->>'$.winner' FROM lecture_reports WHERE id=?")
                    .param(report.id()).query(String.class).single()).isEqualTo(originalWon ? "original" : "retry");
        }
        assertThat(activeSlots()).isZero();
        assertThat(attempts.dueDeletes(10)).containsExactlyInAnyOrder("resp_first", "resp_retry");
        attempts.finishDelete("resp_first");
        attempts.deferDelete("resp_retry");
        assertThat(attempts.dueDeletes(10)).isEmpty();
        assertThat(attempts.findByResponseId("resp_retry")).isEmpty();
    }

    @Test
    void 늦은_최초_시도의_실패는_현재_재시도를_실패시키지_않는다() {
        var report = report();
        var first = reserve(report);
        attempts.unknown(first.attemptKey());
        assertThat(requests.retryEligibleReport(report.room())).isTrue();
        var retry = reserve(report);
        attempts.failRemote(attach(first, "resp_first"), "INVALID_REPORT_EVIDENCE", "INVALID_REPORT_EVIDENCE");
        assertThat(status(report)).isEqualTo("PROCESSING");
        assertThat(activeSlots()).isOne();
        assertThat(attempts.complete(attach(retry, "resp_retry"), "{}")).isTrue();
        assertThat(attempts.dueDeletes(10)).containsExactlyInAnyOrder("resp_first","resp_retry");
    }

    @Test
    void 완료_저장이나_오류_알림_저장에_실패하면_삭제_대기와_슬롯_해제도_롤백한다() {
        var report = report();
        var attempt = reserve(report);
        var attached = attach(attempt, "resp_atomic");
        assertThatThrownBy(() -> attempts.complete(attached, "invalid json")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> attempts.failRemote(attached, "LLM_REQUEST_FAILED", "X".repeat(65)))
                .isInstanceOf(DataAccessException.class);
        assertThat(status(report)).isEqualTo("PROCESSING");
        assertThat(activeSlots()).isOne();
        assertThat(attempts.dueDeletes(10)).isEmpty();
        assertThat(attempts.findByResponseId("resp_atomic")).isPresent();
        assertThat(attempts.complete(attached, "{}")).isTrue();
        assertThat(attempts.dueDeletes(10)).containsExactly("resp_atomic");
    }

    @Test
    void webhook_중복과_POST_응답_순서가_바뀌어도_다른_보고서에는_연결되지_않는다() {
        var a = reserve(report());
        var b = reserve(report());
        attempts.enqueueWebhook("resp_case");
        attempts.enqueueWebhook("resp_case");
        attempts.enqueueWebhook("resp_CASE");
        assertThat(attempts.dueWebhooks(1)).containsExactly("resp_case");
        assertThat(attempts.dueWebhooks(1)).containsExactly("resp_CASE");
        assertThat(attempts.dueWebhooks(5)).isEmpty();
        attach(a, "resp_case");
        assertThat(attempts.attach(b.attemptKey(), "resp_case")).isEmpty();
        assertThat(attempts.attach(a.attemptKey(), "resp_other")).isEmpty();
        assertThat(attach(a, "resp_case").attemptKey()).isEqualTo(a.attemptKey());
        attempts.finishWebhook("resp_case");
        attempts.deferWebhook("resp_CASE");
        assertThat(attempts.findByResponseId("resp_case")).isPresent();
        assertThat(attempts.findByResponseId("resp_CASE")).isEmpty();
    }

    @Test
    void UNKNOWN_30일_후_입력은_삭제하고_늦은_ID는_삭제만_예약하며_수동_재시도는_유지한다() {
        for (boolean periodic : List.of(true, false)) {
            var report = report();
            var attempt = reserve(report);
            attempts.unknown(attempt.attemptKey());
            assertThat(jdbc.sql("""
                    SELECT TIMESTAMPDIFF(DAY,UTC_TIMESTAMP(6),next_action_at) FROM report_generation_attempts WHERE attempt_key=?
                    """).param(attempt.attemptKey().toString()).query(Integer.class).single()).isBetween(29, 30);
            jdbc.sql("UPDATE report_generation_attempts SET next_action_at=UTC_TIMESTAMP(6) WHERE attempt_key=?")
                    .param(attempt.attemptKey().toString()).update();
            if (periodic) {
                reports.failAbandonedJobs(10);
                assertThat(error(report)).isEqualTo("REPORT_DISPATCH_EXPIRED");
            }
            var lateId = "resp_expired_" + report.id();
            assertThat(attempts.attach(attempt.attemptKey(), lateId)).isEmpty();
            assertThat(error(report)).isEqualTo("REPORT_DISPATCH_EXPIRED");
            assertThat(jdbc.sql("SELECT input_json IS NULL FROM report_generation_attempts WHERE response_id=?")
                    .param(lateId).query(Boolean.class).single()).isTrue();
            assertThat(attempts.dueResponses(10)).isEmpty();
            assertThat(attempts.dueDeletes(10)).containsExactly(lateId);
            attempts.finishDelete(lateId);
            assertThat(requests.retryEligibleReport(report.room())).isTrue();
        }
    }

    @Test
    void 원격_metadata가_바뀌면_해당_ID만_분리하고_원문_저장과_원격_삭제를_막는다() {
        var report = report();
        var attempt = reserve(report);
        var attached = attach(attempt, "resp_unverified");
        attempts.unbindUnverifiedResponse(attached.attemptKey(), "resp_wrong");
        assertThat(activeSlots()).isOne();
        attempts.unbindUnverifiedResponse(attached.attemptKey(), "resp_unverified");
        assertThat(activeSlots()).isZero();
        assertThat(attempts.findByResponseId("resp_unverified")).isEmpty();
        assertThat(attempts.dueDeletes(10)).isEmpty();
        assertThat(error(report)).isEqualTo("REPORT_DISPATCH_UNKNOWN");
        // Even after the first alert was delivered, a later terminal failure of the same attempt sends no second alert.
        jdbc.sql("DELETE FROM report_alert_outbox WHERE report_id=?").param(report.id()).update();
        var verified = attach(attempt, "resp_verified");
        assertThat(attempts.complete(attached, "{}")).isFalse();
        attempts.failRemote(attached, "LLM_REQUEST_FAILED", "OLD_GET");
        attempts.fail(attempt.attemptKey(), "LLM_REQUEST_FAILED", "OLD_POST");
        assertThat(status(report)).isEqualTo("PROCESSING");
        assertThat(attempts.dueDeletes(10)).isEmpty();
        attempts.failRemote(verified, "LLM_REQUEST_FAILED", "OPENAI_BACKGROUND_FAILURE");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM report_alert_outbox WHERE report_id=?")
                .param(report.id()).query(Integer.class).single()).isZero();
        assertThat(attempts.dueDeletes(10)).containsExactly("resp_verified");
    }

    @Test
    void 재시도_실패_후에도_첫_시도가_복구_가능한_동안만_모든_조회가_미확인으로_표시한다() {
        for (String resolution : List.of("complete", "failed", "expired")) {
            var report = report();
            var first = reserve(report);
            attempts.unknown(first.attemptKey());
            assertThat(requests.retryEligibleReport(report.room())).isTrue();
            var retry = attach(reserve(report), "resp_retry_" + resolution);
            attempts.failRemote(retry, "INVALID_REPORT_EVIDENCE", "INVALID_REPORT_EVIDENCE");
            assertThat(error(report)).isEqualTo("INVALID_REPORT_EVIDENCE");
            assertFailureViews(report, FailureCategory.UNCONFIRMED);
            if (resolution.equals("expired")) {
                jdbc.sql("UPDATE report_generation_attempts SET next_action_at=UTC_TIMESTAMP(6) WHERE attempt_key=?")
                        .param(first.attemptKey().toString()).update();
                reports.failAbandonedJobs(10);
            } else {
                var recovered = attach(first, "resp_original_" + resolution);
                assertFailureViews(report, FailureCategory.UNCONFIRMED);
                if (resolution.equals("complete")) {
                    assertThat(attempts.complete(recovered, "{}")).isTrue();
                    assertThat(status(report)).isEqualTo("COMPLETED");
                    assertFailureViews(report, null);
                    continue;
                }
                attempts.failRemote(recovered, "LLM_REQUEST_FAILED", "OPENAI_BACKGROUND_FAILURE");
            }
            assertThat(error(report)).isEqualTo("INVALID_REPORT_EVIDENCE");
            assertFailureViews(report, FailureCategory.AI_OUTPUT);
        }
    }

    private void assertFailureViews(Report report, FailureCategory expected) {
        var view = queries.status(report.room(), "attempt@test.local");
        assertThat(view.failureCategory()).isEqualTo(expected);
        assertThat(view.retryAvailable()).isFalse();
        assertThat(queries.detail(report.room(), "attempt@test.local").failureCategory()).isEqualTo(expected);
        assertThat(requests.findByRoom(report.room()).orElseThrow().failureCategory()).isEqualTo(expected);
        boolean unconfirmed = expected == FailureCategory.UNCONFIRMED;
        assertThat(history.page(host, null, null, 100)).filteredOn(row -> row.roomId() == report.room()).singleElement()
                .satisfies(row -> assertThat(row.reportUnconfirmed()).isEqualTo(unconfirmed));
        assertThat(history.statuses(host, List.of(report.room()))).singleElement()
                .satisfies(row -> assertThat(row.reportUnconfirmed()).isEqualTo(unconfirmed));
    }

    private Report report() {
        long room = insert("INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at) VALUES(?,'test',UUID(),'ENDED',NOW())", host);
        long id = insert("INSERT INTO lecture_reports(lecture_room_id,status,created_at) VALUES(?,'PENDING',NOW())", room);
        return new Report(id, room);
    }

    private Attempt reserve(Report report) {
        return attempts.reserve(report.id(), "{\"input\":\"original\"}").orElseThrow();
    }

    private Attempt attach(Attempt attempt, String id) {
        return attempts.attach(attempt.attemptKey(), id).orElseThrow();
    }

    private int activeSlots() {
        return jdbc.sql("SELECT COUNT(*) FROM report_generation_slots WHERE attempt_key IS NOT NULL").query(Integer.class).single();
    }

    private String status(Report report) {
        return jdbc.sql("SELECT status FROM lecture_reports WHERE id=?").param(report.id()).query(String.class).single();
    }

    private String error(Report report) {
        return jdbc.sql("SELECT error_message FROM lecture_reports WHERE id=?").param(report.id()).query(String.class).single();
    }

    private long insert(String sql, Object... params) {
        var key = new GeneratedKeyHolder();
        jdbc.sql(sql).params(params).update(key);
        return key.getKey().longValue();
    }
}
