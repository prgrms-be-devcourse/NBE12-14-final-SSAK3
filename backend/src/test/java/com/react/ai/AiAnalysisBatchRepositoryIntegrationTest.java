package com.react.ai;

import com.react.ai.domain.AiAnalysisBatchStatus;
import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.domain.TopicCategory;
import com.react.ai.dto.AiAnalysisResult;
import com.react.ai.dto.AiTopicResult;
import com.react.ai.dto.AudienceAnalysisInputMessage;
import com.react.ai.dto.AudienceAnalysisBatch;
import com.react.ai.dto.PreprocessedAudienceMessages;
import com.react.ai.repository.AiAnalysisBatchRepository;
import com.react.ai.repository.AiAnalysisRepository;
import com.react.ai.service.AudienceAnalysisBatchSplitter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AiAnalysisBatchRepositoryIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired AiAnalysisBatchRepository batches;
    @Autowired AiAnalysisRepository analyses;
    @Autowired AudienceAnalysisBatchSplitter splitter;

    private long analysisId;

    @BeforeEach
    void prepareAnalysis() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).isEqualTo("react_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        String key = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO users(email, password_hash, name, created_at) VALUES (?, 'hash', 'host', NOW())")
                .param(key + "@example.com").update();
        long hostId = jdbc.sql("SELECT id FROM users WHERE email = ?").param(key + "@example.com")
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO lecture_rooms(host_user_id, title, join_code, status, created_at, started_at)
                VALUES (?, '배치 테스트', ?, 'LIVE', NOW(), ?)
                """).params(hostId, key, LocalDateTime.of(2026, 11, 17, 10, 0)).update();
        long roomId = jdbc.sql("SELECT id FROM lecture_rooms WHERE join_code = ?").param(key)
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO ai_analyses(lecture_room_id, window_start_at, window_end_at, status, created_at)
                VALUES (?, ?, ?, 'PROCESSING', CURRENT_TIMESTAMP)
                """).params(roomId, LocalDateTime.of(2026, 11, 17, 10, 0),
                LocalDateTime.of(2026, 11, 17, 10, 0, 30)).update();
        analysisId = jdbc.sql("SELECT id FROM ai_analyses WHERE lecture_room_id = ?")
                .param(roomId).query(Long.class).single();
    }

    @Test
    void 입력_manifest를_순서와_원본_ID_지문까지_보존하고_원문은_복제하지_않는다() {
        batches.createPending(analysisId, splitter.split(input(101), 100, 32_000));

        var stored = batches.findByAnalysis(analysisId);
        assertThat(stored).hasSize(2);
        assertThat(stored).extracting(AiAnalysisBatchRepository.Batch::batchIndex).containsExactly(0, 1);
        assertThat(stored).extracting(AiAnalysisBatchRepository.Batch::status)
                .containsExactly(AiAnalysisBatchStatus.PENDING, AiAnalysisBatchStatus.PENDING);
        assertThat(stored.getFirst().inputMessageCount()).isEqualTo(100);
        assertThat(stored.getLast().inputMessageCount()).isEqualTo(1);
        assertThat(stored.getFirst().inputManifest().messages().getFirst().originalMessageIds())
                .containsExactly(1L);
        assertThat(stored.getLast().inputManifest().messages().getFirst().representativeMessageId())
                .isEqualTo(101L);
        assertThat(stored.getFirst().inputManifest().messages().getFirst().contentSha256()).hasSize(64);
        assertThat(jdbc.sql("SELECT CAST(input_manifest AS CHAR) FROM ai_analysis_batches WHERE id = ?")
                .param(stored.getFirst().id()).query(String.class).single()).doesNotContain("질문1");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_topic_groups WHERE analysis_id = ?")
                .param(analysisId).query(Integer.class).single()).isZero();
    }

    @Test
    void 중복_배치_저장은_기존_입력을_덮어쓰지_않고_전체_트랜잭션을_롤백한다() {
        batches.createPending(analysisId, splitter.split(input(1), 100, 32_000));

        assertThatThrownBy(() -> batches.createPending(analysisId, splitter.split(input(101), 100, 32_000)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(batches.findByAnalysis(analysisId)).hasSize(1);
        assertThat(batches.findByAnalysis(analysisId).getFirst().inputManifest().messages())
                .hasSize(1);
    }

    @Test
    void 배치_초기화_중_두번째_manifest가_잘못되면_첫_INSERT까지_롤백하고_부모는_PENDING으로_남는다() {
        jdbc.sql("UPDATE ai_analyses SET status = 'PENDING', processing_started_at = NULL WHERE id = ?")
                .param(analysisId).update();
        var first = batch(0, 1L);
        var invalidSecond = batch(0, 2L);

        assertThatThrownBy(() -> batches.initializeForProcessing(analysisId, List.of(first, invalidSecond)))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);

        assertThat(batches.findByAnalysis(analysisId)).isEmpty();
        assertThat(jdbc.sql("SELECT status FROM ai_analyses WHERE id = ?").param(analysisId)
                .query(String.class).single()).isEqualTo(AiAnalysisStatus.PENDING.name());
        assertThat(jdbc.sql("SELECT processing_started_at FROM ai_analyses WHERE id = ?").param(analysisId)
                .query(java.time.LocalDateTime.class).optional()).isEmpty();
    }

    @Test
    void 배치_권한을_얻은_작업자만_검증된_결과를_저장하고_종결할_수_있다() {
        batches.createPending(analysisId, splitter.split(input(2), 1, 32_000));
        var stored = batches.findByAnalysis(analysisId);
        UUID firstToken = UUID.randomUUID();
        UUID otherToken = UUID.randomUUID();
        long firstId = stored.getFirst().id();
        long secondId = stored.getLast().id();

        assertThat(batches.claim(firstId, firstToken, Duration.ofSeconds(30))).isTrue();
        assertThat(batches.claim(firstId, otherToken, Duration.ofSeconds(30))).isFalse();
        var result = new AiAnalysisResult(List.of(new AiTopicResult("질문", "질문이 나왔습니다.",
                TopicCategory.QUESTION, List.of(1L))));
        assertThat(batches.saveValidatedResult(firstId, otherToken, result, "test-model")).isFalse();
        assertThat(batches.saveValidatedResult(firstId, firstToken, result, "test-model")).isTrue();
        assertThat(batches.saveValidatedResult(firstId, firstToken, result, "test-model")).isFalse();
        assertThat(batches.claim(secondId, otherToken, Duration.ofSeconds(30))).isTrue();
        assertThat(batches.markFailed(secondId, otherToken, "HTTP_5XX")).isTrue();

        var after = batches.findByAnalysis(analysisId);
        assertThat(after.getFirst().status()).isEqualTo(AiAnalysisBatchStatus.COMPLETED);
        assertThat(after.getFirst().result()).isEqualTo(result);
        assertThat(after.getFirst().completedAt()).isNotNull();
        assertThat(after.getLast().status()).isEqualTo(AiAnalysisBatchStatus.FAILED);
        assertThat(after.getLast().result()).isNull();
        assertThat(after.getLast().errorCode()).isEqualTo("HTTP_5XX");
        assertThat(jdbc.sql("SELECT status FROM ai_analyses WHERE id = ?")
                .param(analysisId).query(String.class).single()).isEqualTo("PROCESSING");
    }

    @Test
    void stale_복구와_배치_초기화가_경합해도_FAILED_부모_아래_고아_배치를_남기지_않는다() throws Exception {
        jdbc.sql("""
                UPDATE ai_analyses
                SET status = 'PENDING', processing_started_at = NULL,
                    created_at = DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 3 MINUTE)
                WHERE id = ?
                """).param(analysisId).update();
        var pending = splitter.split(input(101), 100, 32_000);
        CountDownLatch startTogether = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var initialization = executor.submit(() -> {
                assertThat(startTogether.await(5, TimeUnit.SECONDS)).isTrue();
                return batches.initializeForProcessing(analysisId, pending);
            });
            var staleRecovery = executor.submit(() -> {
                assertThat(startTogether.await(5, TimeUnit.SECONDS)).isTrue();
                return analyses.abortStaleInFlight(Duration.ofMinutes(2));
            });
            startTogether.countDown();
            initialization.get(10, TimeUnit.SECONDS);
            staleRecovery.get(10, TimeUnit.SECONDS);
        }

        String status = jdbc.sql("SELECT status FROM ai_analyses WHERE id = ?").param(analysisId)
                .query(String.class).single();
        int batchCount = jdbc.sql("SELECT COUNT(*) FROM ai_analysis_batches WHERE analysis_id = ?")
                .param(analysisId).query(Integer.class).single();
        assertThat(status).isIn(AiAnalysisStatus.PROCESSING.name(), AiAnalysisStatus.FAILED.name());
        if (status.equals(AiAnalysisStatus.FAILED.name())) {
            assertThat(batchCount).isZero();
        } else {
            assertThat(batchCount).isEqualTo(2);
        }
    }

    @Test
    void 이미_종결된_부모에는_배치를_생성하지_않는다() {
        for (AiAnalysisStatus terminal : List.of(AiAnalysisStatus.FAILED, AiAnalysisStatus.COMPLETED)) {
            jdbc.sql("UPDATE ai_analyses SET status = ? WHERE id = ?").params(terminal.name(), analysisId).update();

            var initialized = batches.initializeForProcessing(analysisId, splitter.split(input(101), 100, 32_000));

            assertThat(initialized.status()).isEqualTo(terminal);
            assertThat(initialized.initialized()).isFalse();
            assertThat(batches.findByAnalysis(analysisId)).isEmpty();
        }
    }

    private PreprocessedAudienceMessages input(int count) {
        List<AudienceAnalysisInputMessage> messages = new ArrayList<>();
        Map<Long, List<Long>> originals = new LinkedHashMap<>();
        for (long id = 1; id <= count; id++) {
            messages.add(new AudienceAnalysisInputMessage(id, "질문" + id, 1));
            originals.put(id, List.of(id));
        }
        return new PreprocessedAudienceMessages(messages, originals);
    }

    private AudienceAnalysisBatch batch(int index, long messageId) {
        return new AudienceAnalysisBatch(index,
                List.of(new AudienceAnalysisInputMessage(messageId, "질문" + messageId, 1)),
                Map.of(messageId, List.of(messageId)));
    }
}
