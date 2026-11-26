package com.react.report;

import com.react.report.repository.ReportAlertRepository;
import com.react.report.repository.ReportGenerationRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportAlertRepositoryIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired JdbcClient jdbc;
    @Autowired ReportGenerationRepository reports;
    @Autowired ReportAlertRepository alerts;
    private Flyway flyway;
    private long hostId;

    @BeforeAll
    void createIsolatedAlertQueue() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).isEqualTo("react_test");
        flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        hostId = insert("INSERT INTO users(email,password_hash,name,created_at) VALUES ('alert@test.local','hash','host',NOW())");
    }

    @AfterEach
    void clearReportsAndTheirAlerts() {
        jdbc.sql("DELETE FROM lecture_reports").update();
        assertThat(alertCount()).isZero();
    }

    @AfterAll
    void cleanDatabase() {
        if (flyway != null) flyway.clean();
    }

    @Test
    void 실패가_처음_확정될_때만_접수하고_알림_저장_실패시_상태도_롤백한다() {
        long id = processingReport();
        assertThat(reports.failAiGeneration(id, "LLM_REQUEST_FAILED", "MODEL_UNAVAILABLE")).isTrue();
        assertThat(reports.failAiGeneration(id, "LLM_REQUEST_FAILED", "MODEL_UNAVAILABLE")).isFalse();
        assertThat(alertCount()).isOne();

        long another = processingReport();
        assertThatThrownBy(() -> reports.failAiGeneration(another, "LLM_REQUEST_FAILED", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.sql("SELECT status FROM lecture_reports WHERE id = ?").param(another)
                .query(String.class).single()).isEqualTo("PROCESSING");
        assertThat(alertCount()).isOne();
    }

    @Test
    void 첫_실패_알림을_확인해도_그_동안_발생한_재시도_실패는_전송_대상에_남는다() {
        long id = processingReport();
        reports.failAiGeneration(id, "LLM_TEMPORARY_FAILURE", "TEMPORARY_FAILURE");
        UUID first = UUID.randomUUID();
        assertThat(alerts.claim(first)).extracting(ReportAlertRepository.Alert::retried).containsExactly(false);

        jdbc.sql("UPDATE lecture_reports SET status = 'PROCESSING', retry_used = TRUE WHERE id = ?").param(id).update();
        reports.failAiGeneration(id, "INVALID_REPORT_FORMAT", "INVALID_REPORT_FORMAT");
        alerts.acknowledge(first);
        assertThat(alerts.claim(UUID.randomUUID())).singleElement().satisfies(alert -> {
            assertThat(alert.reportId()).isEqualTo(id);
            assertThat(alert.retried()).isTrue();
            assertThat(alert.diagnosticCode()).isEqualTo("INVALID_REPORT_FORMAT");
        });
    }

    @Test
    void 선점이_만료되면_인계하고_이전_작업자의_확인과_연기는_새_선점에_영향이_없다() {
        long id = processingReport();
        reports.failAiGeneration(id, "LLM_REQUEST_FAILED", "MODEL_UNAVAILABLE");
        UUID oldToken = UUID.randomUUID();
        var original = alerts.claim(oldToken);
        assertThat(original).singleElement().satisfies(alert -> {
            assertThat(alert.reportId()).isEqualTo(id);
            assertThat(alert.retried()).isFalse();
            assertThat(alert.diagnosticCode()).isEqualTo("MODEL_UNAVAILABLE");
            assertThat(alert.failedAt()).isEqualTo(jdbc.sql("SELECT created_at FROM report_alert_outbox WHERE report_id = ?")
                    .param(id).query(LocalDateTime.class).single().toInstant(ZoneOffset.UTC));
        });
        assertThat(alerts.claim(UUID.randomUUID())).isEmpty();

        makeDue(id);
        UUID newToken = UUID.randomUUID();
        assertThat(alerts.claim(newToken)).isEqualTo(original);
        alerts.acknowledge(oldToken);
        alerts.defer(oldToken);
        assertThat(jdbc.sql("SELECT lease_token FROM report_alert_outbox WHERE report_id = ?")
                .param(id).query(String.class).single()).isEqualTo(newToken.toString());

        alerts.defer(newToken);
        assertThat(alerts.claim(UUID.randomUUID())).isEmpty();
        assertThat(jdbc.sql("""
                SELECT lease_token IS NULL AND next_attempt_at > UTC_TIMESTAMP(6) + INTERVAL 4 MINUTE
                FROM report_alert_outbox WHERE report_id = ?
                """).param(id).query(Boolean.class).single()).isTrue();
        makeDue(id);
        UUID lastToken = UUID.randomUUID();
        assertThat(alerts.claim(lastToken)).isEqualTo(original);
        alerts.acknowledge(lastToken);
        assertThat(alertCount()).isZero();
    }

    @Test
    void 두_작업자가_동시에_선점하면_서로_겹치지_않는_최대_20건을_가져간다() throws Exception {
        var ids = new ArrayList<Long>();
        for (int i = 0; i < 21; i++) {
            long id = processingReport();
            ids.add(id);
            reports.failAiGeneration(id, "LLM_TEMPORARY_FAILURE", "TEMPORARY_FAILURE");
        }
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CyclicBarrier(2);
            Callable<List<ReportAlertRepository.Alert>> claim = () -> {
                start.await(10, TimeUnit.SECONDS);
                return alerts.claim(UUID.randomUUID());
            };
            var first = pool.submit(claim);
            var second = pool.submit(claim);
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertThat(List.of(a.size(), b.size())).containsExactlyInAnyOrder(20, 1);
            assertThat(Stream.concat(a.stream(), b.stream()).map(ReportAlertRepository.Alert::reportId))
                    .containsExactlyInAnyOrderElementsOf(ids);
        }
    }

    private int alertCount() {
        return jdbc.sql("SELECT COUNT(*) FROM report_alert_outbox").query(Integer.class).single();
    }

    private void makeDue(long id) {
        jdbc.sql("UPDATE report_alert_outbox SET next_attempt_at = UTC_TIMESTAMP(6) WHERE report_id = ?").param(id).update();
    }

    private long processingReport() {
        long roomId = insert("""
                INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at) VALUES (?,'test',UUID(),'ENDED',NOW())
                """, hostId);
        return insert("INSERT INTO lecture_reports(lecture_room_id,status,created_at) VALUES (?,'PROCESSING',NOW())", roomId);
    }

    private long insert(String sql, Object... params) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql(sql).params(params).update(keys);
        return keys.getKey().longValue();
    }
}
