package com.react.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.ai.config.GeminiAudienceAnalysisProperties;
import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.domain.TopicCategory;
import com.react.ai.dto.AiAnalysisResult;
import com.react.ai.dto.AudienceAnalysisLlmResponse;
import com.react.ai.dto.AiTopicResult;
import com.react.ai.repository.AiAnalysisRepository;
import com.react.ai.repository.AudienceMessageRepository;
import com.react.ai.service.AiAnalysisJsonParser;
import com.react.ai.service.AudienceAnalysisService;
import com.react.ai.service.GeminiAudienceAnalysisLlmClient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import org.springframework.http.MediaType;

@SpringBootTest
@ActiveProfiles("test")
class AudienceAnalysisIntegrationTest {
    private static final String GEMINI_MODEL = "gemini-3.5-flash-lite";
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired AiAnalysisRepository analyses;
    @Autowired AudienceMessageRepository messages;
    @Autowired PlatformTransactionManager transactionManager;

    private final LocalDateTime start = LocalDateTime.of(2026, 11, 17, 10, 0);
    private final LocalDateTime end = start.plusSeconds(30);
    private long roomId;
    private long participantId;

    @BeforeEach
    void prepareRoom() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        String key = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO users(email, password_hash, name, created_at) VALUES (?, 'hash', 'host', NOW())")
                .param(key + "@example.com").update();
        long hostId = jdbc.sql("SELECT id FROM users WHERE email = ?").param(key + "@example.com")
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO lecture_rooms(host_user_id, title, join_code, status, created_at, started_at)
                VALUES (?, '강연', ?, 'LIVE', NOW(), ?)
                """).params(hostId, key, start).update();
        roomId = jdbc.sql("SELECT id FROM lecture_rooms WHERE join_code = ?").param(key).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO lecture_participants(lecture_room_id, nickname, participant_token_hash, joined_at)
                VALUES (?, '청중', ?, NOW())
                """).params(roomId, key + "-participant").update();
        participantId = jdbc.sql("SELECT id FROM lecture_participants WHERE participant_token_hash = ?")
                .param(key + "-participant").query(Long.class).single();
    }

    @Test
    void 청중_원문을_연결해_완료_결과를_저장하고_같은_구간은_재호출하지_않는다() {
        long first = insertAudienceMessage("Redis는 왜 쓰나요?", start);
        long second = insertAudienceMessage("MySQL만 쓰는 예시도 궁금합니다.", start.plusSeconds(29));
        long nextWindowMessage = insertAudienceMessage("다음 구간 메시지", end);
        insertHostMessage("강연자 메시지는 분석 대상이 아닙니다.", start.plusSeconds(10));
        var calls = new AtomicInteger();
        var service = service(messages -> {
            calls.incrementAndGet();
            return response("""
                    {"topics":[{"topic":"Redis 사용 이유","summary":"도입 이유와 대안을 궁금해했습니다.",
                    "category":"MIXED","sourceMessageIds":[%d,%d]}]}
                    """.formatted(first, second));
        });

        var created = service.process(roomId, start, end);
        var repeated = service.process(roomId, start, end);

        assertThat(created.status()).isEqualTo(AiAnalysisStatus.COMPLETED);
        assertThat(created.topicGroupCount()).isEqualTo(1);
        assertThat(repeated.created()).isFalse();
        assertThat(calls).hasValue(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_topic_group_messages WHERE chat_message_id IN (?, ?)")
                .params(first, second).query(Long.class).single()).isEqualTo(2L);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_topic_group_messages WHERE chat_message_id = ?")
                .param(nextWindowMessage).query(Long.class).single()).isZero();
    }

    @Test
    void Gemini_어댑터_응답을_기존_코어로_저장하고_잘못된_JSON은_FAILED로_기록한다() {
        long messageId = insertAudienceMessage("Redis는 왜 사용하나요?", start);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://gemini.test/v1beta/models/" + GEMINI_MODEL + ":generateContent"))
                .andRespond(withSuccess("""
                        {"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"{\\"topics\\":[{\\"topic\\":\\"Redis 사용 이유\\",\\"summary\\":\\"도입 이유를 질문했습니다.\\",\\"category\\":\\"QUESTION\\",\\"sourceMessageIds\\":[%d]}]}"}]}}]}
                        """.formatted(messageId), MediaType.APPLICATION_JSON));
        var service = service(geminiClient(builder.build()));

        var completed = service.process(roomId, start, end);

        assertThat(completed.status()).isEqualTo(AiAnalysisStatus.COMPLETED);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_topic_groups WHERE analysis_id = ?").param(completed.analysisId())
                .query(Long.class).single()).isEqualTo(1L);

        LocalDateTime nextEnd = end.plusSeconds(30);
        insertAudienceMessage("다음 구간 메시지", end);
        RestClient.Builder malformedBuilder = RestClient.builder();
        MockRestServiceServer malformedServer = MockRestServiceServer.bindTo(malformedBuilder).build();
        malformedServer.expect(requestTo("https://gemini.test/v1beta/models/" + GEMINI_MODEL + ":generateContent"))
                .andRespond(withSuccess("""
                        {"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"{topics:[]}"}]}}]}
                        """, MediaType.APPLICATION_JSON));

        var failed = service(geminiClient(malformedBuilder.build())).process(roomId, end, nextEnd);

        assertThat(failed.status()).isEqualTo(AiAnalysisStatus.FAILED);
        assertThat(jdbc.sql("SELECT error_message FROM ai_analyses WHERE id = ?").param(failed.analysisId())
                .query(String.class).single()).isEqualTo("INVALID_LLM_RESPONSE");

        LocalDateTime finalEnd = nextEnd.plusSeconds(30);
        insertAudienceMessage("출력이 잘린 다음 구간 메시지", nextEnd);
        RestClient.Builder truncatedBuilder = RestClient.builder();
        MockRestServiceServer truncatedServer = MockRestServiceServer.bindTo(truncatedBuilder).build();
        truncatedServer.expect(requestTo("https://gemini.test/v1beta/models/" + GEMINI_MODEL + ":generateContent"))
                .andRespond(withSuccess("""
                        {"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"{\\"topics\\":[]}"}]}}]}
                        """, MediaType.APPLICATION_JSON));

        var truncated = service(geminiClient(truncatedBuilder.build())).process(roomId, nextEnd, finalEnd);

        assertThat(truncated.status()).isEqualTo(AiAnalysisStatus.FAILED);
        assertThat(jdbc.sql("SELECT status FROM ai_analyses WHERE id = ?").param(truncated.analysisId())
                .query(String.class).single()).isEqualTo("FAILED");
    }

    @Test
    void Gemini_429_재시도_성공은_하나의_분석과_주제_그룹만_저장한다() {
        long messageId = insertAudienceMessage("재시도할 질문", start);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://gemini.test/v1beta/models/" + GEMINI_MODEL + ":generateContent"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo("https://gemini.test/v1beta/models/" + GEMINI_MODEL + ":generateContent"))
                .andRespond(withSuccess(geminiResponse("""
                        {"topics":[{"topic":"재시도 주제","summary":"재시도 후 정상 요약","category":"QUESTION","sourceMessageIds":[%d]}]}
                        """.formatted(messageId)), MediaType.APPLICATION_JSON));

        var completed = service(geminiClient(builder.build())).process(roomId, start, end);

        assertThat(completed.status()).isEqualTo(AiAnalysisStatus.COMPLETED);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_analyses WHERE lecture_room_id = ? AND window_start_at = ?")
                .params(roomId, start).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_topic_groups WHERE analysis_id = ?")
                .param(completed.analysisId()).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_topic_group_messages m "
                        + "JOIN ai_topic_groups g ON g.id = m.topic_group_id WHERE g.analysis_id = ?")
                .param(completed.analysisId()).query(Long.class).single()).isEqualTo(1L);
        server.verify();
    }

    @Test
    void 빈_구간은_LLM_호출_없이_모델없이_완료로_저장한다() {
        var calls = new AtomicInteger();
        var service = service(messages -> {
            calls.incrementAndGet();
            return response("{\"topics\":[]}");
        });

        var result = service.process(roomId, start, end);

        assertThat(result.status()).isEqualTo(AiAnalysisStatus.COMPLETED);
        assertThat(calls).hasValue(0);
        assertThat(jdbc.sql("SELECT status FROM ai_analyses WHERE id = ?").param(result.analysisId())
                .query(String.class).single()).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT model_name FROM ai_analyses WHERE id = ?").param(result.analysisId())
                .query(String.class).optional()).isEmpty();
        assertThat(jdbc.sql("SELECT completed_at FROM ai_analyses WHERE id = ?").param(result.analysisId())
                .query(LocalDateTime.class).single()).isNotNull();
    }

    @Test
    void 서버_종료로_남은_stale_PENDING과_PROCESSING만_FAILED로_한번_종결한다() {
        var stalePending = analyses.acquire(roomId, start, end);
        LocalDateTime processingStart = end;
        LocalDateTime processingEnd = processingStart.plusSeconds(30);
        var staleProcessing = analyses.acquire(roomId, processingStart, processingEnd);
        assertThat(analyses.markProcessing(staleProcessing.analysis().id())).isTrue();

        LocalDateTime freshStart = processingEnd;
        var freshProcessing = analyses.acquire(roomId, freshStart, freshStart.plusSeconds(30));
        assertThat(analyses.markProcessing(freshProcessing.analysis().id())).isTrue();

        LocalDateTime completedStart = freshStart.plusSeconds(30);
        var completed = analyses.acquire(roomId, completedStart, completedStart.plusSeconds(30));
        jdbc.sql("UPDATE ai_analyses SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP WHERE id = ?")
                .param(completed.analysis().id()).update();

        makeCreatedAtStale(stalePending.analysis().id());
        makeCreatedAtStale(staleProcessing.analysis().id());
        makeCreatedAtStale(completed.analysis().id());

        assertThat(analyses.abortStaleInFlight(Duration.ofMinutes(2))).isGreaterThanOrEqualTo(2);
        LocalDateTime firstCompletedAt = jdbc.sql("SELECT completed_at FROM ai_analyses WHERE id = ?")
                .param(staleProcessing.analysis().id()).query(LocalDateTime.class).single();
        analyses.abortStaleInFlight(Duration.ofMinutes(2));

        assertAnalysis(stalePending.analysis().id(), "FAILED", "STALE_ANALYSIS_ABORTED", true);
        assertAnalysis(staleProcessing.analysis().id(), "FAILED", "STALE_ANALYSIS_ABORTED", true);
        assertThat(jdbc.sql("SELECT completed_at FROM ai_analyses WHERE id = ?")
                .param(staleProcessing.analysis().id()).query(LocalDateTime.class).single()).isEqualTo(firstCompletedAt);
        assertAnalysis(freshProcessing.analysis().id(), "PROCESSING", null, false);
        assertAnalysis(completed.analysis().id(), "COMPLETED", null, true);
    }

    @Test
    void stale_복구_후_늦게_도착한_Gemini_결과는_FAILED를_덮어쓰지_않고_그룹_저장은_롤백된다() {
        long messageId = insertAudienceMessage("늦게 도착한 Gemini 응답", start);
        var acquired = analyses.acquire(roomId, start, end);
        assertThat(analyses.markProcessing(acquired.analysis().id())).isTrue();
        makeCreatedAtStale(acquired.analysis().id());

        assertThat(analyses.abortStaleInFlight(Duration.ofMinutes(2))).isGreaterThanOrEqualTo(1);
        AiAnalysisResult result = new AiAnalysisResult(java.util.List.of(
                new AiTopicResult("주제", "늦은 요약", TopicCategory.QUESTION, java.util.List.of(messageId))));

        assertThatThrownBy(() -> analyses.saveCompleted(acquired.analysis().id(), result, "fake-model"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("분석 완료 상태로 변경할 수 없습니다.");

        assertAnalysis(acquired.analysis().id(), "FAILED", "STALE_ANALYSIS_ABORTED", true);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_topic_groups WHERE analysis_id = ?")
                .param(acquired.analysis().id()).query(Long.class).single()).isZero();
    }

    @Test
    void 완료_저장이_먼저_커밋되면_동시_stale_복구는_COMPLETED_분석을_변경하지_않는다() throws Exception {
        long messageId = insertAudienceMessage("완료 저장 우선 메시지", start);
        var acquired = analyses.acquire(roomId, start, end);
        assertThat(analyses.markProcessing(acquired.analysis().id())).isTrue();
        makeCreatedAtStale(acquired.analysis().id());
        AiAnalysisResult result = resultFor(messageId, "완료 저장 우선");

        CountDownLatch completedUpdateApplied = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CountDownLatch recoveryAttempted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> save = executor.submit(() -> inTransaction(() -> {
                lockAnalysisRow(acquired.analysis().id());
                analyses.saveCompleted(acquired.analysis().id(), result, "fake-model");
                completedUpdateApplied.countDown();
                awaitLatch(allowCommit, "완료 저장 커밋 허용");
            }));

            assertThat(completedUpdateApplied.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Integer> recovery = executor.submit(() -> {
                recoveryAttempted.countDown();
                return analyses.abortStaleInFlight(Duration.ofMinutes(2));
            });
            assertThat(recoveryAttempted.await(5, TimeUnit.SECONDS)).isTrue();

            allowCommit.countDown();
            save.get(5, TimeUnit.SECONDS);
            recovery.get(5, TimeUnit.SECONDS);
        } finally {
            shutdown(executor);
        }

        assertAnalysis(acquired.analysis().id(), "COMPLETED", null, true);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_topic_groups WHERE analysis_id = ?")
                .param(acquired.analysis().id()).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_topic_group_messages m "
                        + "JOIN ai_topic_groups g ON g.id = m.topic_group_id WHERE g.analysis_id = ?")
                .param(acquired.analysis().id()).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void stale_복구가_먼저_커밋되면_동시_완료_저장은_롤백되고_FAILED를_덮어쓰지_않는다() throws Exception {
        long messageId = insertAudienceMessage("복구 우선 메시지", start);
        var acquired = analyses.acquire(roomId, start, end);
        assertThat(analyses.markProcessing(acquired.analysis().id())).isTrue();
        makeCreatedAtStale(acquired.analysis().id());
        AiAnalysisResult result = resultFor(messageId, "복구 저장 우선");

        CountDownLatch recoveryUpdateApplied = new CountDownLatch(1);
        CountDownLatch allowRecoveryCommit = new CountDownLatch(1);
        CountDownLatch saveAttempted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> recovery = executor.submit(() -> inTransactionWithResult(() -> {
                lockAnalysisRow(acquired.analysis().id());
                int changed = analyses.abortStaleInFlight(Duration.ofMinutes(2));
                recoveryUpdateApplied.countDown();
                awaitLatch(allowRecoveryCommit, "stale 복구 커밋 허용");
                return changed;
            }));

            assertThat(recoveryUpdateApplied.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> save = executor.submit(() -> {
                saveAttempted.countDown();
                analyses.saveCompleted(acquired.analysis().id(), result, "fake-model");
            });
            assertThat(saveAttempted.await(5, TimeUnit.SECONDS)).isTrue();

            allowRecoveryCommit.countDown();
            recovery.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> save.get(5, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("분석 완료 상태로 변경할 수 없습니다.");
        } finally {
            shutdown(executor);
        }

        assertAnalysis(acquired.analysis().id(), "FAILED", "STALE_ANALYSIS_ABORTED", true);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_topic_groups WHERE analysis_id = ?")
                .param(acquired.analysis().id()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_topic_group_messages m "
                        + "JOIN ai_topic_groups g ON g.id = m.topic_group_id WHERE g.analysis_id = ?")
                .param(acquired.analysis().id()).query(Long.class).single()).isZero();
    }

    @Test
    void 두_stale_복구가_동시에_실행되어도_분석은_한번만_FAILED로_변경된다() throws Exception {
        var acquired = analyses.acquire(roomId, start, end);
        assertThat(analyses.markProcessing(acquired.analysis().id())).isTrue();
        makeCreatedAtStale(acquired.analysis().id());

        CountDownLatch startTogether = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> abortAfter(startTogether));
            Future<Integer> second = executor.submit(() -> abortAfter(startTogether));
            startTogether.countDown();

            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            shutdown(executor);
        }

        assertAnalysis(acquired.analysis().id(), "FAILED", "STALE_ANALYSIS_ABORTED", true);
    }

    @Test
    void LIVE와_finalization이_동시에_같은_구간을_처리해도_Gemini는_한번만_호출된다() throws Exception {
        long messageId = insertAudienceMessage("동시 분석 메시지", start);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch llmStarted = new CountDownLatch(1);
        CountDownLatch allowResponse = new CountDownLatch(1);
        AudienceAnalysisService concurrentService = service(messages -> {
            calls.incrementAndGet();
            llmStarted.countDown();
            try {
                if (!allowResponse.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("테스트 Gemini 응답 대기 시간이 초과되었습니다.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return response("""
                    {"topics":[{"topic":"동시성","summary":"동시 분석 테스트","category":"QUESTION","sourceMessageIds":[%d]}]}
                    """.formatted(messageId));
        });
        CountDownLatch startTogether = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var live = executor.submit(() -> processAfter(startTogether, concurrentService));
            var finalization = executor.submit(() -> processAfter(startTogether, concurrentService));

            startTogether.countDown();
            assertThat(llmStarted.await(5, TimeUnit.SECONDS)).isTrue();
            allowResponse.countDown();

            live.get(5, TimeUnit.SECONDS);
            finalization.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(calls).hasValue(1);
        assertThat(jdbc.sql("SELECT status FROM ai_analyses WHERE lecture_room_id = ? AND window_start_at = ?")
                .params(roomId, start).query(String.class).single()).isEqualTo("COMPLETED");
    }

    @Test
    void 다른_강연방의_메시지_참조는_실패로_기록하고_그룹을_저장하지_않는다() {
        long otherRoomMessage = insertMessageInOtherRoom();
        insertAudienceMessage("현재 강연 질문", start);
        var service = service(messages -> response("""
                {"topics":[{"topic":"주제","summary":"요약","category":"QUESTION","sourceMessageIds":[%d]}]}
                """.formatted(otherRoomMessage)));

        var result = service.process(roomId, start, end);

        assertThat(result.status()).isEqualTo(AiAnalysisStatus.FAILED);
        assertThat(jdbc.sql("SELECT status FROM ai_analyses WHERE id = ?").param(result.analysisId())
                .query(String.class).single()).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT error_message FROM ai_analyses WHERE id = ?").param(result.analysisId())
                .query(String.class).single()).isEqualTo("INVALID_LLM_RESPONSE");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_topic_groups WHERE analysis_id = ?").param(result.analysisId())
                .query(Long.class).single()).isZero();
    }

    @Test
    void 그룹과_원문_연결_저장_실패는_트랜잭션으로_롤백된다() {
        long messageId = insertAudienceMessage("질문", start);
        var acquired = analyses.acquire(roomId, start, end);
        assertThat(analyses.markProcessing(acquired.analysis().id())).isTrue();
        var invalidResult = new AiAnalysisResult(java.util.List.of(
                new AiTopicResult("주제", "요약", TopicCategory.QUESTION, java.util.List.of(messageId, messageId))));

        assertThatThrownBy(() -> analyses.saveCompleted(acquired.analysis().id(), invalidResult, "fake-model"))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_topic_groups WHERE analysis_id = ?").param(acquired.analysis().id())
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT status FROM ai_analyses WHERE id = ?").param(acquired.analysis().id())
                .query(String.class).single()).isEqualTo("PROCESSING");
    }

    private AudienceAnalysisService service(com.react.ai.service.AudienceAnalysisLlmClient llm) {
        return new AudienceAnalysisService(analyses, messages, llm, new AiAnalysisJsonParser(new ObjectMapper()));
    }

    private AiAnalysisResult resultFor(long messageId, String summary) {
        return new AiAnalysisResult(java.util.List.of(
                new AiTopicResult("동시성 주제", summary, TopicCategory.QUESTION, java.util.List.of(messageId))));
    }

    private void lockAnalysisRow(long analysisId) {
        jdbc.sql("SELECT id FROM ai_analyses WHERE id = ? FOR UPDATE")
                .param(analysisId).query(Long.class).single();
    }

    private void inTransaction(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
    }

    private <T> T inTransactionWithResult(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    private int abortAfter(CountDownLatch startTogether) {
        awaitLatch(startTogether, "동시 stale 복구 시작");
        return analyses.abortStaleInFlight(Duration.ofMinutes(2));
    }

    private void awaitLatch(CountDownLatch latch, String purpose) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(purpose + " 대기 시간이 초과되었습니다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(purpose + " 대기 중 인터럽트되었습니다.", e);
        }
    }

    private void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    private GeminiAudienceAnalysisLlmClient geminiClient(RestClient restClient) {
        return new GeminiAudienceAnalysisLlmClient(restClient, new ObjectMapper(),
                new GeminiAudienceAnalysisProperties("test-key", GEMINI_MODEL, "https://gemini.test/v1beta",
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 100, 32_000, 2_048, 128_000));
    }

    private AudienceAnalysisLlmResponse response(String content) {
        return new AudienceAnalysisLlmResponse(content, "fake-model");
    }

    private String geminiResponse(String text) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            return """
                    {"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":%s}]}}]}
                    """.formatted(mapper.writeValueAsString(text));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private long insertAudienceMessage(String content, LocalDateTime receivedAt) {
        jdbc.sql("""
                INSERT INTO chat_messages(lecture_room_id, participant_id, content, received_at)
                VALUES (?, ?, ?, ?)
                """).params(roomId, participantId, content, receivedAt).update();
        return jdbc.sql("SELECT id FROM chat_messages WHERE lecture_room_id = ? ORDER BY id DESC LIMIT 1")
                .param(roomId).query(Long.class).single();
    }

    private void makeCreatedAtStale(long analysisId) {
        jdbc.sql("UPDATE ai_analyses SET created_at = DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 121 SECOND) WHERE id = ?")
                .param(analysisId).update();
    }

    private void assertAnalysis(long analysisId, String expectedStatus, String expectedErrorMessage,
                                boolean completedAtExpected) {
        assertThat(jdbc.sql("SELECT status FROM ai_analyses WHERE id = ?").param(analysisId)
                .query(String.class).single()).isEqualTo(expectedStatus);
        var errorMessage = jdbc.sql("SELECT error_message FROM ai_analyses WHERE id = ?").param(analysisId)
                .query(String.class).optional();
        if (expectedErrorMessage == null) {
            assertThat(errorMessage).isEmpty();
        } else {
            assertThat(errorMessage).contains(expectedErrorMessage);
        }
        if (completedAtExpected) {
            assertThat(jdbc.sql("SELECT completed_at FROM ai_analyses WHERE id = ?").param(analysisId)
                    .query(LocalDateTime.class).single()).isNotNull();
        }
    }

    private com.react.ai.dto.AiAnalysisProcessResult processAfter(CountDownLatch startTogether,
                                                                    AudienceAnalysisService service) {
        try {
            if (!startTogether.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 시작 대기 시간이 초과되었습니다.");
            }
            return service.process(roomId, start, end);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void insertHostMessage(String content, LocalDateTime receivedAt) {
        long hostId = jdbc.sql("SELECT host_user_id FROM lecture_rooms WHERE id = ?").param(roomId).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO chat_messages(lecture_room_id, host_user_id, content, received_at)
                VALUES (?, ?, ?, ?)
                """).params(roomId, hostId, content, receivedAt).update();
    }

    private long insertMessageInOtherRoom() {
        String key = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO users(email, password_hash, name, created_at) VALUES (?, 'hash', 'other', NOW())")
                .param(key + "@example.com").update();
        long hostId = jdbc.sql("SELECT id FROM users WHERE email = ?").param(key + "@example.com")
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO lecture_rooms(host_user_id, title, join_code, status, created_at)
                VALUES (?, '다른 강연', ?, 'LIVE', NOW())
                """).params(hostId, key).update();
        long otherRoomId = jdbc.sql("SELECT id FROM lecture_rooms WHERE join_code = ?").param(key).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO lecture_participants(lecture_room_id, nickname, participant_token_hash, joined_at)
                VALUES (?, '다른 청중', ?, NOW())
                """).params(otherRoomId, key + "-participant").update();
        long otherParticipantId = jdbc.sql("SELECT id FROM lecture_participants WHERE participant_token_hash = ?")
                .param(key + "-participant").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO chat_messages(lecture_room_id, participant_id, content, received_at)
                VALUES (?, ?, '다른 강연 메시지', ?)
                """).params(otherRoomId, otherParticipantId, start).update();
        return jdbc.sql("SELECT id FROM chat_messages WHERE lecture_room_id = ? ORDER BY id DESC LIMIT 1")
                .param(otherRoomId).query(Long.class).single();
    }
}
