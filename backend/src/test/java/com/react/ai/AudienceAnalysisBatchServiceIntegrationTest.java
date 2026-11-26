package com.react.ai;

import com.react.ai.config.AudienceAnalysisCostProperties;
import com.react.ai.config.AudienceAnalysisRetryProperties;
import com.react.ai.domain.AiAnalysisBatchStatus;
import com.react.ai.domain.TopicCategory;
import com.react.ai.dto.AiAnalysisResult;
import com.react.ai.dto.AiTopicResult;
import com.react.ai.dto.AudienceAnalysisLlmResponse;
import com.react.ai.exception.GeminiAudienceAnalysisErrorCode;
import com.react.ai.exception.GeminiAudienceAnalysisException;
import com.react.ai.repository.AiAnalysisBatchAttemptRepository;
import com.react.ai.repository.AiAnalysisBatchRepository;
import com.react.ai.repository.AiAnalysisRepository;
import com.react.ai.repository.AiLlmCallUsageRepository;
import com.react.ai.repository.AudienceMessageRepository;
import com.react.ai.service.AiAnalysisJsonParser;
import com.react.ai.service.AudienceAnalysisBatchInputRestorer;
import com.react.ai.service.AudienceAnalysisBatchService;
import com.react.ai.service.AudienceAnalysisBatchSplitter;
import com.react.ai.service.AudienceAnalysisLlmClient;
import com.react.ai.service.AudienceMessagePreprocessor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AudienceAnalysisBatchServiceIntegrationTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 17, 10, 0);
    private static final Duration LEASE = Duration.ofSeconds(60);

    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired AiAnalysisRepository analyses;
    @Autowired AiAnalysisBatchRepository batches;
    @Autowired AiAnalysisBatchAttemptRepository attempts;
    @Autowired AudienceMessageRepository messages;
    @Autowired AudienceMessagePreprocessor preprocessor;
    @Autowired AudienceAnalysisBatchSplitter splitter;
    @Autowired AudienceAnalysisBatchInputRestorer restorer;
    @Autowired AiAnalysisJsonParser parser;
    @Autowired AiLlmCallUsageRepository usages;

    private long roomId;
    private long participantId;
    private long analysisId;

    @BeforeEach
    void prepare() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).isEqualTo("react_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        String key = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO users(email, password_hash, name, created_at) VALUES (?, 'hash', 'host', NOW())")
                .param(key + "@example.com").update();
        long hostId = jdbc.sql("SELECT id FROM users WHERE email = ?")
                .param(key + "@example.com").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO lecture_rooms(host_user_id, title, join_code, status, created_at, started_at)
                VALUES (?, '배치 실행 테스트', ?, 'LIVE', NOW(), ?)
                """).params(hostId, key, START).update();
        roomId = jdbc.sql("SELECT id FROM lecture_rooms WHERE join_code = ?")
                .param(key).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO lecture_participants(lecture_room_id, nickname, participant_token_hash, joined_at)
                VALUES (?, '청중', ?, NOW())
                """).params(roomId, key + "-p").update();
        participantId = jdbc.sql("SELECT id FROM lecture_participants WHERE participant_token_hash = ?")
                .param(key + "-p").query(Long.class).single();
        var acquired = analyses.acquire(roomId, START, START.plusSeconds(30));
        analysisId = acquired.analysis().id();
        assertThat(analyses.markProcessing(analysisId)).isTrue();
    }

    @Test
    void 대표_메시지_101개를_두_배치로_분석하고_최종_그룹은_공개하지_않는다() {
        prepareBatches(101);
        AtomicInteger calls = new AtomicInteger();
        var result = service(inputs -> {
            calls.incrementAndGet();
            return response(inputs.getFirst().messageId());
        }).process(analysisId, LEASE);

        assertThat(result.outcome()).isEqualTo(AudienceAnalysisBatchService.BatchOutcome.ALL_BATCHES_COMPLETED);
        assertThat(result.completedInThisRun()).isEqualTo(2);
        assertThat(calls).hasValue(2);
        assertThat(batches.findByAnalysis(analysisId)).extracting(AiAnalysisBatchRepository.Batch::status)
                .containsExactly(AiAnalysisBatchStatus.COMPLETED, AiAnalysisBatchStatus.COMPLETED);
        assertThat(usageAttemptNumbers()).containsExactly(1, 2);
        assertNoFinalGroups();
    }

    @Test
    void 첫_배치_성공_뒤_두번째_배치가_재시도까지_실패해도_성공_결과는_남는다() {
        prepareBatches(101);
        AtomicInteger calls = new AtomicInteger();
        AudienceAnalysisBatchService service = service(inputs -> {
            if (calls.incrementAndGet() == 1) {
                return response(inputs.getFirst().messageId());
            }
            throw new GeminiAudienceAnalysisException(GeminiAudienceAnalysisErrorCode.HTTP_5XX,
                    true, null, "테스트용 서버 오류");
        });

        assertThat(service.process(analysisId, LEASE).outcome())
                .isEqualTo(AudienceAnalysisBatchService.BatchOutcome.FAILED);
        assertThat(batches.findByAnalysis(analysisId)).extracting(AiAnalysisBatchRepository.Batch::status)
                .containsExactly(AiAnalysisBatchStatus.COMPLETED, AiAnalysisBatchStatus.FAILED);
        assertThat(batches.findByAnalysis(analysisId).getFirst().result()).isNotNull();
        assertThat(batches.findByAnalysis(analysisId).getLast().errorCode()).isEqualTo("HTTP_5XX");
        assertThat(usageAttemptNumbers()).containsExactly(1, 2, 3);
        assertThat(service.process(analysisId, LEASE).outcome())
                .isEqualTo(AudienceAnalysisBatchService.BatchOutcome.FAILED);
        assertThat(calls).hasValue(3);
        assertNoFinalGroups();
    }

    @Test
    void 저장된_입력과_원본_내용이_달라지면_Gemini_호출_없이_실패한다() {
        prepareBatches(101);
        long id = batches.findByAnalysis(analysisId).getFirst().inputManifest().messages().getFirst()
                .representativeMessageId();
        jdbc.sql("UPDATE chat_messages SET content = '바뀐 질문' WHERE id = ?").param(id).update();
        AtomicInteger calls = new AtomicInteger();

        assertThat(service(inputs -> {
            calls.incrementAndGet();
            return response(inputs.getFirst().messageId());
        }).process(analysisId, LEASE).outcome()).isEqualTo(AudienceAnalysisBatchService.BatchOutcome.FAILED);
        assertThat(calls).hasValue(0);
        assertThat(batches.findByAnalysis(analysisId).getFirst().errorCode()).isEqualTo("INPUT_MANIFEST_MISMATCH");
        assertThat(usageAttemptNumbers()).isEmpty();
    }

    @Test
    void 원본_메시지가_다른_구간으로_이동하면_저장된_ID를_복원하지_않는다() {
        prepareBatches(1);
        long id = batches.findByAnalysis(analysisId).getFirst().inputManifest().messages().getFirst()
                .representativeMessageId();
        jdbc.sql("UPDATE chat_messages SET received_at = ? WHERE id = ?")
                .params(START.plusSeconds(30), id).update();
        AtomicInteger calls = new AtomicInteger();
        assertThat(service(inputs -> {
            calls.incrementAndGet();
            return response(inputs.getFirst().messageId());
        }).process(analysisId, LEASE).outcome()).isEqualTo(AudienceAnalysisBatchService.BatchOutcome.FAILED);
        assertThat(calls).hasValue(0);
        assertThat(batches.findByAnalysis(analysisId).getFirst().errorCode()).isEqualTo("INPUT_MANIFEST_MISMATCH");
    }

    @Test
    void Gemini가_배치에_없는_대표_ID를_반환하면_검증_실패_결과를_저장하지_않는다() {
        prepareBatches(1);
        assertThat(service(inputs -> response(999_999L)).process(analysisId, LEASE).outcome())
                .isEqualTo(AudienceAnalysisBatchService.BatchOutcome.FAILED);
        var batch = batches.findByAnalysis(analysisId).getFirst();
        assertThat(batch.status()).isEqualTo(AiAnalysisBatchStatus.FAILED);
        assertThat(batch.errorCode()).isEqualTo("INVALID_LLM_RESPONSE");
        assertThat(batch.result()).isNull();
        assertNoFinalGroups();
    }

    @Test
    void 성공_배치는_새_서비스_실행에서도_재호출하지_않는다() {
        prepareBatches(101);
        var first = batches.findByAnalysis(analysisId).getFirst();
        UUID token = UUID.randomUUID();
        assertThat(batches.claim(first.id(), token, LEASE)).isTrue();
        long representativeId = first.inputManifest().messages().getFirst().representativeMessageId();
        assertThat(batches.saveValidatedResult(first.id(), token,
                new AiAnalysisResult(List.of(new AiTopicResult("질문", "질문이 나왔습니다.",
                        TopicCategory.QUESTION, List.of(representativeId)))), "fake-model")).isTrue();
        AtomicInteger calls = new AtomicInteger();

        assertThat(service(inputs -> {
            calls.incrementAndGet();
            return response(inputs.getFirst().messageId());
        }).process(analysisId, LEASE).outcome())
                .isEqualTo(AudienceAnalysisBatchService.BatchOutcome.ALL_BATCHES_COMPLETED);
        assertThat(calls).hasValue(1);
        assertThat(batches.findByAnalysis(analysisId).getFirst().result()).isNotNull();
        assertNoFinalGroups();
    }

    @Test
    void 동시에_같은_배치를_claim해도_한_작업자만_획득한다() throws Exception {
        prepareBatches(1);
        long batchId = batches.findByAnalysis(analysisId).getFirst().id();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return batches.claim(batchId, UUID.randomUUID(), LEASE); });
            var b = executor.submit(() -> { start.await(); return batches.claim(batchId, UUID.randomUUID(), LEASE); });
            start.countDown();
            assertThat(List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    void lease가_만료된_옛_작업자는_결과를_저장하지_못한다() {
        prepareBatches(1);
        long batchId = batches.findByAnalysis(analysisId).getFirst().id();
        UUID oldToken = UUID.randomUUID();
        UUID newToken = UUID.randomUUID();
        assertThat(batches.claim(batchId, oldToken, LEASE)).isTrue();
        jdbc.sql("UPDATE ai_analysis_batches SET lease_until = DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND) WHERE id = ?")
                .param(batchId).update();
        var result = new AiAnalysisResult(List.of());
        assertThat(batches.saveValidatedResult(batchId, oldToken, result, "fake-model")).isFalse();
        assertThat(batches.claim(batchId, newToken, LEASE)).isTrue();
        assertThat(batches.saveValidatedResult(batchId, oldToken, result, "fake-model")).isFalse();
        assertThat(batches.saveValidatedResult(batchId, newToken, result, "fake-model")).isTrue();
    }

    @Test
    void 두_작업자가_같은_배치를_실행해도_Fake_LLM은_한번만_호출된다() throws Exception {
        prepareBatches(1);
        CountDownLatch llmStarted = new CountDownLatch(1);
        CountDownLatch allowFinish = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var worker = service(inputs -> {
            calls.incrementAndGet();
            llmStarted.countDown();
            try {
                if (!allowFinish.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Fake LLM 대기 시간 초과");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Fake LLM 대기 중단", e);
            }
            return response(inputs.getFirst().messageId());
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> worker.process(analysisId, LEASE));
            assertThat(llmStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> worker.process(analysisId, LEASE));
            assertThat(second.get(5, TimeUnit.SECONDS).outcome())
                    .isEqualTo(AudienceAnalysisBatchService.BatchOutcome.IN_PROGRESS);
            allowFinish.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).outcome())
                    .isEqualTo(AudienceAnalysisBatchService.BatchOutcome.ALL_BATCHES_COMPLETED);
        } finally {
            allowFinish.countDown();
        }
        assertThat(calls).hasValue(1);
    }

    @Test
    void 재시도_가능한_429_뒤_성공하면_한_배치_결과와_서로_다른_호출_번호를_남긴다() {
        prepareBatches(1);
        AtomicInteger calls = new AtomicInteger();
        var result = service(inputs -> {
            if (calls.incrementAndGet() == 1) {
                throw new GeminiAudienceAnalysisException(GeminiAudienceAnalysisErrorCode.RATE_LIMIT,
                        true, Duration.ZERO, "테스트용 요청 제한");
            }
            return response(inputs.getFirst().messageId());
        }).process(analysisId, LEASE);

        assertThat(result.outcome()).isEqualTo(AudienceAnalysisBatchService.BatchOutcome.ALL_BATCHES_COMPLETED);
        assertThat(calls).hasValue(2);
        assertThat(usageAttemptNumbers()).containsExactly(1, 2);
        assertThat(batches.findByAnalysis(analysisId).getFirst().status()).isEqualTo(AiAnalysisBatchStatus.COMPLETED);
        assertNoFinalGroups();
    }

    private AudienceAnalysisBatchService service(AudienceAnalysisLlmClient llm) {
        return new AudienceAnalysisBatchService(analyses, batches, attempts, restorer, llm, parser, usages,
                new AudienceAnalysisRetryProperties(1, Duration.ZERO, Duration.ofSeconds(5)),
                new AudienceAnalysisCostProperties(null, null));
    }

    private AudienceAnalysisLlmResponse response(long id) {
        return new AudienceAnalysisLlmResponse("""
                {"topics":[{"topic":"질문","summary":"질문이 나왔습니다.","category":"QUESTION","sourceMessageIds":[%d]}]}
                """.formatted(id), "fake-model");
    }

    private void prepareBatches(int count) {
        for (int i = 0; i < count; i++) {
            jdbc.sql("""
                    INSERT INTO chat_messages(lecture_room_id, participant_id, content, received_at)
                    VALUES (?, ?, ?, ?)
                    """).params(roomId, participantId, "질문 " + i, START.plusSeconds(1)).update();
        }
        batches.createPending(analysisId, splitter.split(
                preprocessor.preprocess(messages.findAudienceMessages(roomId, START, START.plusSeconds(30))),
                100, 32_000));
    }

    private List<Integer> usageAttemptNumbers() {
        return jdbc.sql("SELECT attempt_number FROM ai_llm_call_usages WHERE analysis_id = ? ORDER BY attempt_number")
                .param(analysisId).query(Integer.class).list();
    }

    private void assertNoFinalGroups() {
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_topic_groups WHERE analysis_id = ?")
                .param(analysisId).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT status FROM ai_analyses WHERE id = ?")
                .param(analysisId).query(String.class).single()).isEqualTo("PROCESSING");
    }
}
