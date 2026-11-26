package com.react.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.ai.config.AiAnalysisSchedulerProperties;
import com.react.ai.config.AudienceAnalysisCostProperties;
import com.react.ai.config.AudienceAnalysisRetryProperties;
import com.react.ai.config.GeminiAudienceAnalysisProperties;
import com.react.ai.domain.AiAnalysisBatchStatus;
import com.react.ai.dto.AudienceAnalysisInputMessage;
import com.react.ai.dto.AudienceAnalysisLlmResponse;
import com.react.ai.event.AiAnalysisCompletedDomainEvent;
import com.react.ai.event.AiAnalysisFinalized;
import com.react.ai.exception.GeminiAudienceAnalysisErrorCode;
import com.react.ai.exception.GeminiAudienceAnalysisException;
import com.react.ai.repository.AiAnalysisBatchAttemptRepository;
import com.react.ai.repository.AiAnalysisBatchRepository;
import com.react.ai.repository.AiAnalysisFinalizationRepository;
import com.react.ai.repository.AiAnalysisRepository;
import com.react.ai.repository.AiAnalysisTargetRoomRepository;
import com.react.ai.repository.AiLlmCallUsageRepository;
import com.react.ai.repository.AudienceMessageRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@RecordApplicationEvents
class AudienceAnalysisBatchEndToEndIntegrationTest {
    private static final LocalDateTime START = LocalDateTime.of(2002, 1, 1, 10, 0);
    private static final AiAnalysisSchedulerProperties SCHEDULER = new AiAnalysisSchedulerProperties(3);
    private static final AudienceAnalysisRetryProperties RETRY =
            new AudienceAnalysisRetryProperties(1, Duration.ZERO, Duration.ofSeconds(5));
    private static final AudienceAnalysisCostProperties COST = new AudienceAnalysisCostProperties(null, null);

    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired AiAnalysisRepository analyses;
    @Autowired AiAnalysisBatchRepository batches;
    @Autowired AiAnalysisBatchAttemptRepository attempts;
    @Autowired AiAnalysisFinalizationRepository finalizations;
    @Autowired AudienceMessageRepository messages;
    @Autowired AudienceMessagePreprocessor preprocessor;
    @Autowired AudienceAnalysisBatchSplitter splitter;
    @Autowired AudienceAnalysisBatchInputRestorer restorer;
    @Autowired AiAnalysisJsonParser parser;
    @Autowired AiAnalysisCompletionService completions;
    @Autowired AiLlmCallUsageRepository usages;
    @Autowired ObjectMapper objectMapper;
    @Autowired ApplicationEventPublisher events;
    @Autowired ApplicationEvents recordedEvents;

    private Room room;

    @BeforeEach
    void prepare() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).isEqualTo("react_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        room = insertRoom();
    }

    @Test
    void 대표_101개는_두_배치로_완료하고_원문과_호출_이력을_한_분석에_저장한다() {
        List<Long> ids = insertDistinctMessages(101);
        FakeLlm llm = new FakeLlm();
        runLive(service(llm));
        long id = analysisId();

        assertStatus("COMPLETED");
        assertThat(llm.calls()).isEqualTo(2);
        assertThat(batches.findByAnalysis(id)).extracting(AiAnalysisBatchRepository.Batch::status)
                .containsExactly(AiAnalysisBatchStatus.COMPLETED, AiAnalysisBatchStatus.COMPLETED);
        assertThat(linkedIds(id)).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(count("SELECT COUNT(*) FROM ai_analyses WHERE id = ?", id)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM ai_llm_call_usages WHERE analysis_id = ?", id)).isEqualTo(2);
        assertThat(recordedEvents.stream(AiAnalysisCompletedDomainEvent.class)
                .filter(event -> event.analysisId() == id).count()).isEqualTo(1);
    }

    @Test
    void 본문_32001자는_분할하고_100개_이하와_빈_구간은_기존_경로를_유지한다() {
        insertMessage("A".repeat(31_999), START.plusSeconds(1));
        insertMessage("AB", START.plusSeconds(2));
        FakeLlm llm = new FakeLlm();
        service(llm).process(room.id(), START, START.plusSeconds(30));
        assertStatus("COMPLETED");
        assertThat(batches.findByAnalysis(analysisId())).hasSize(2);
        assertThat(llm.calls()).isEqualTo(2);

        insertMessage("질문 한 개", START.plusSeconds(35));
        var single = service(llm).process(room.id(), START.plusSeconds(30), START.plusSeconds(60));
        assertThat(single.status().name()).isEqualTo("COMPLETED");
        assertThat(batches.findByAnalysis(single.analysisId())).isEmpty();
        assertThat(llm.calls()).isEqualTo(3);

        var empty = service(llm).process(room.id(), START.plusSeconds(60), START.plusSeconds(90));
        assertThat(empty.status().name()).isEqualTo("COMPLETED");
        assertThat(batches.findByAnalysis(empty.analysisId())).isEmpty();
        assertThat(llm.calls()).isEqualTo(3);
    }

    @Test
    void 두번째_배치가_실패해도_첫번째_결과는_남고_최종_그룹은_공개되지_않는다() {
        insertDistinctMessages(101);
        FakeLlm llm = new FakeLlm();
        llm.failAfterFirst = true;
        service(llm).process(room.id(), START, START.plusSeconds(30));
        long id = analysisId();

        assertStatus("FAILED");
        assertThat(llm.calls()).isEqualTo(3);
        assertThat(batches.findByAnalysis(id)).extracting(AiAnalysisBatchRepository.Batch::status)
                .containsExactly(AiAnalysisBatchStatus.COMPLETED, AiAnalysisBatchStatus.FAILED);
        assertThat(batches.findByAnalysis(id).getFirst().result()).isNotNull();
        assertThat(count("SELECT COUNT(*) FROM ai_topic_groups WHERE analysis_id = ?", id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM ai_llm_call_usages WHERE analysis_id = ?", id)).isEqualTo(3);
        service(llm).process(room.id(), START, START.plusSeconds(30));
        assertThat(llm.calls()).isEqualTo(3);
    }

    @Test
    void 첫_배치_완료_뒤_중단되면_재개할_때_나머지만_호출한다() {
        insertDistinctMessages(101);
        FakeLlm llm = new FakeLlm();
        AtomicInteger checks = new AtomicInteger();
        var stopped = service(llm).process(room.id(), START, START.plusSeconds(30),
                () -> checks.incrementAndGet() < 2);
        assertThat(stopped.status().name()).isEqualTo("PROCESSING");
        assertThat(llm.calls()).isEqualTo(1);
        assertThat(batches.findByAnalysis(analysisId())).extracting(AiAnalysisBatchRepository.Batch::status)
                .containsExactly(AiAnalysisBatchStatus.COMPLETED, AiAnalysisBatchStatus.PENDING);

        runLive(service(llm));
        assertStatus("COMPLETED");
        assertThat(llm.calls()).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM ai_llm_call_usages WHERE analysis_id = ?", analysisId()))
                .isEqualTo(2);
    }

    @Test
    void 배치_초기화_DB_오류로_PENDING이_남아도_다음_LIVE_실행에서_재개한다() {
        insertDistinctMessages(101);
        FakeLlm llm = new FakeLlm();
        var service = service(llm, new FailOnceInitializationBatchRepository(batches, jdbc, objectMapper));

        var failedInitialization = service.process(room.id(), START, START.plusSeconds(30));

        assertThat(failedInitialization.status().name()).isEqualTo("PENDING");
        assertStatus("PENDING");
        assertThat(batches.findByAnalysis(analysisId())).isEmpty();
        assertThat(llm.calls()).isZero();

        runLive(service);

        assertStatus("COMPLETED");
        assertThat(batches.findByAnalysis(analysisId())).hasSize(2);
        assertThat(llm.calls()).isEqualTo(2);
    }

    @Test
    void 종료된_강연의_PENDING_배치도_Finalization으로_재개한다() {
        insertDistinctMessages(101);
        FakeLlm llm = new FakeLlm();
        var service = service(llm, new FailOnceInitializationBatchRepository(batches, jdbc, objectMapper));
        service.process(room.id(), START, START.plusSeconds(30));
        assertStatus("PENDING");
        jdbc.sql("UPDATE lecture_rooms SET status = 'ENDED', ended_at = ? WHERE id = ?")
                .params(START.plusSeconds(30), room.id()).update();

        new AiAnalysisFinalizationService(finalizations, analyses, service, new AiAnalysisWindowCalculator(), events)
                .processPendingRooms(5, 3, Duration.ofSeconds(90));

        assertStatus("COMPLETED");
        assertThat(finalizations.find(room.id()).status().name()).isEqualTo("COMPLETED");
        assertThat(llm.calls()).isEqualTo(2);
    }

    @Test
    void PENDING_단일_분석은_재개_확인만하고_Gemini를_자동_재호출하지_않는다() {
        insertMessage("짧은 질문", START.plusSeconds(1));
        analyses.acquire(room.id(), START, START.plusSeconds(30));
        FakeLlm llm = new FakeLlm();

        runLive(service(llm));

        assertStatus("PENDING");
        assertThat(batches.findByAnalysis(analysisId())).isEmpty();
        assertThat(llm.calls()).isZero();
    }

    @Test
    void FAILED와_COMPLETED_부모는_재실행하지_않는다() {
        insertDistinctMessages(101);
        var failed = analyses.acquire(room.id(), START, START.plusSeconds(30));
        assertThat(analyses.markProcessing(failed.analysis().id())).isTrue();
        assertThat(analyses.markFailed(failed.analysis().id(), "TEST")).isTrue();
        jdbc.sql("""
                INSERT INTO ai_analyses(lecture_room_id, window_start_at, window_end_at, status, created_at, completed_at)
                VALUES (?, ?, ?, 'COMPLETED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """).params(room.id(), START.plusSeconds(30), START.plusSeconds(60)).update();
        FakeLlm llm = new FakeLlm();

        service(llm).process(room.id(), START, START.plusSeconds(30));
        service(llm).process(room.id(), START.plusSeconds(30), START.plusSeconds(60));

        assertThat(llm.calls()).isZero();
        assertThat(batches.findByAnalysis(failed.analysis().id())).isEmpty();
    }

    @Test
    void manifest_저장_전_중단으로_배치_없는_PROCESSING이_남아도_다시_만들어_완료한다() {
        insertDistinctMessages(101);
        var acquired = analyses.acquire(room.id(), START, START.plusSeconds(30));
        assertThat(analyses.markProcessing(acquired.analysis().id())).isTrue();
        FakeLlm llm = new FakeLlm();

        var result = service(llm).process(room.id(), START, START.plusSeconds(30));

        assertThat(result.status().name()).isEqualTo("COMPLETED");
        assertThat(batches.findByAnalysis(acquired.analysis().id())).hasSize(2);
        assertThat(llm.calls()).isEqualTo(2);
    }

    @Test
    void 배치_없는_PROCESSING을_동시에_복구해도_manifest는_한번만_생성한다() throws Exception {
        insertDistinctMessages(101);
        var acquired = analyses.acquire(room.id(), START, START.plusSeconds(30));
        assertThat(analyses.markProcessing(acquired.analysis().id())).isTrue();
        FakeLlm llm = new FakeLlm();
        llm.blockFirst = true;
        var service = service(llm);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> service.process(room.id(), START, START.plusSeconds(30)));
            assertThat(llm.entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> service.process(room.id(), START, START.plusSeconds(30)));
            assertThat(second.get(5, TimeUnit.SECONDS).status().name()).isEqualTo("PROCESSING");
            assertThat(batches.findByAnalysis(acquired.analysis().id())).hasSize(2);
            llm.release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).status().name()).isEqualTo("COMPLETED");
        } finally {
            llm.release.countDown();
        }
        assertThat(batches.findByAnalysis(acquired.analysis().id())).hasSize(2);
        assertThat(llm.calls()).isEqualTo(2);
    }

    @Test
    void 종료된_강연도_manifest_없는_PROCESSING_배치를_Finalization으로_복구한다() {
        insertDistinctMessages(101);
        var acquired = analyses.acquire(room.id(), START, START.plusSeconds(30));
        assertThat(analyses.markProcessing(acquired.analysis().id())).isTrue();
        jdbc.sql("UPDATE lecture_rooms SET status = 'ENDED', ended_at = ? WHERE id = ?")
                .params(START.plusSeconds(30), room.id()).update();
        FakeLlm llm = new FakeLlm();
        var finalizer = new AiAnalysisFinalizationService(finalizations, analyses, service(llm),
                new AiAnalysisWindowCalculator(), events);

        finalizer.processPendingRooms(5, 3, Duration.ofSeconds(90));

        assertStatus("COMPLETED");
        assertThat(batches.findByAnalysis(acquired.analysis().id())).hasSize(2);
        assertThat(finalizations.find(room.id()).status().name()).isEqualTo("COMPLETED");
        assertThat(llm.calls()).isEqualTo(2);
    }

    @Test
    void 동시_실행과_stale_복구가_정상_배치를_중복_호출하거나_종결하지_못한다() throws Exception {
        insertDistinctMessages(101);
        FakeLlm llm = new FakeLlm();
        llm.blockFirst = true;
        var service = service(llm);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> service.process(room.id(), START, START.plusSeconds(30)));
            assertThat(llm.entered.await(5, TimeUnit.SECONDS)).isTrue();
            long id = analysisId();
            jdbc.sql("UPDATE ai_analyses SET processing_started_at = DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 3 MINUTE) WHERE id = ?")
                    .param(id).update();
            analyses.abortStaleInFlight(Duration.ofMinutes(2));
            assertThat(analyses.isProcessing(id)).isTrue();
            assertThat(service.process(room.id(), START, START.plusSeconds(30)).status().name())
                    .isEqualTo("PROCESSING");
            llm.release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).status().name()).isEqualTo("COMPLETED");
            assertThat(llm.calls()).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM ai_topic_groups WHERE analysis_id = ?", id)).isEqualTo(2);
        } finally {
            llm.release.countDown();
        }
    }

    @Test
    void 종료_후_미처리_배치를_재개하고_잔여_구간까지_끝내_보고서_준비_계약을_유지한다() {
        insertDistinctMessages(101);
        FakeLlm llm = new FakeLlm();
        AtomicInteger checks = new AtomicInteger();
        service(llm).process(room.id(), START, START.plusSeconds(30), () -> checks.incrementAndGet() < 2);
        jdbc.sql("UPDATE lecture_rooms SET status = 'ENDED', ended_at = ? WHERE id = ?")
                .params(START.plusSeconds(45), room.id()).update();
        var finalizer = new AiAnalysisFinalizationService(finalizations, analyses, service(llm),
                new AiAnalysisWindowCalculator(), events);
        finalizer.processPendingRooms(5, 3, Duration.ofSeconds(90));

        assertStatus("COMPLETED");
        assertThat(llm.calls()).isEqualTo(2);
        assertThat(finalizations.find(room.id()).status().name()).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT status FROM ai_analyses WHERE lecture_room_id = ? AND window_start_at = ?")
                .params(room.id(), START.plusSeconds(30)).query(String.class).single()).isEqualTo("COMPLETED");
        assertThat(recordedEvents.stream(AiAnalysisFinalized.class)
                .filter(event -> event.roomId() == room.id()).count()).isEqualTo(1);
    }

    @Test
    void LIVE_분석_중_강연이_종료돼도_Finalization은_같은_배치를_중복_호출하지_않는다() throws Exception {
        insertDistinctMessages(101);
        FakeLlm llm = new FakeLlm();
        llm.blockFirst = true;
        var service = service(llm);
        var finalizer = new AiAnalysisFinalizationService(finalizations, analyses, service,
                new AiAnalysisWindowCalculator(), events);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var live = pool.submit(() -> service.process(room.id(), START, START.plusSeconds(30)));
            assertThat(llm.entered.await(5, TimeUnit.SECONDS)).isTrue();
            jdbc.sql("UPDATE lecture_rooms SET status = 'ENDED', ended_at = ? WHERE id = ?")
                    .params(START.plusSeconds(30), room.id()).update();
            finalizer.processPendingRooms(5, 3, Duration.ofSeconds(90));
            assertThat(llm.calls()).isEqualTo(1);
            assertStatus("PROCESSING");
            llm.release.countDown();
            assertThat(live.get(10, TimeUnit.SECONDS).status().name()).isEqualTo("COMPLETED");
            finalizer.processPendingRooms(5, 3, Duration.ofSeconds(90));
            assertThat(finalizations.find(room.id()).status().name()).isEqualTo("COMPLETED");
            assertThat(llm.calls()).isEqualTo(2);
            assertThat(recordedEvents.stream(AiAnalysisCompletedDomainEvent.class)
                    .filter(event -> event.analysisId() == analysisId()).count()).isEqualTo(1);
        } finally {
            llm.release.countDown();
        }
    }

    private AudienceAnalysisService service(FakeLlm llm) {
        return service(llm, batches);
    }

    private AudienceAnalysisService service(FakeLlm llm, AiAnalysisBatchRepository batchRepository) {
        var batchService = new AudienceAnalysisBatchService(analyses, batchRepository, attempts, restorer, llm,
                parser, usages, RETRY, COST);
        var props = new GeminiAudienceAnalysisProperties("", "fake-model", "", null, null,
                100, 32_000, 2_048, 128_000);
        var coordinator = new AudienceAnalysisBatchCoordinator(analyses, batchRepository, splitter, batchService,
                restorer, parser, completions, props, SCHEDULER);
        return new AudienceAnalysisService(analyses, messages, llm, parser, RETRY, usages,
                COST, completions, preprocessor, coordinator);
    }

    private void runLive(AudienceAnalysisService service) {
        Clock clock = Clock.fixed(START.plusSeconds(31).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                ZoneId.of("Asia/Seoul"));
        AiAnalysisTargetRoomRepository selectedRoom = mock(AiAnalysisTargetRoomRepository.class);
        when(selectedRoom.findLiveRoomsWithStartedAt())
                .thenReturn(List.of(new AiAnalysisTargetRoomRepository.LiveRoom(room.id(), START)));
        new AudienceAnalysisScheduler(selectedRoom, analyses, service, new AiAnalysisWindowCalculator(), clock,
                SCHEDULER, new AiAnalysisSchedulerMetrics(new SimpleMeterRegistry()))
                .processCompletedRegularWindows();
    }

    private Room insertRoom() {
        String key = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO users(email, password_hash, name, created_at) VALUES (?, 'hash', 'host', NOW())")
                .param(key + "@example.com").update();
        long host = jdbc.sql("SELECT id FROM users WHERE email = ?").param(key + "@example.com")
                .query(Long.class).single();
        jdbc.sql("INSERT INTO lecture_rooms(host_user_id, title, join_code, status, created_at, started_at) "
                + "VALUES (?, 'AI 배치 연결 테스트', ?, 'LIVE', NOW(), ?)")
                .params(host, key, START).update();
        long roomId = jdbc.sql("SELECT id FROM lecture_rooms WHERE join_code = ?")
                .param(key).query(Long.class).single();
        jdbc.sql("INSERT INTO lecture_participants(lecture_room_id, nickname, participant_token_hash, joined_at) "
                + "VALUES (?, '청중', ?, NOW())").params(roomId, key + "-p").update();
        long participant = jdbc.sql("SELECT id FROM lecture_participants WHERE participant_token_hash = ?")
                .param(key + "-p").query(Long.class).single();
        return new Room(roomId, participant);
    }

    private List<Long> insertDistinctMessages(int count) {
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            ids.add(insertMessage("Redis 질문 " + index + "?", START.plusSeconds(1)));
        }
        return ids;
    }

    private long insertMessage(String content, LocalDateTime receivedAt) {
        jdbc.sql("INSERT INTO chat_messages(lecture_room_id, participant_id, content, received_at) VALUES (?, ?, ?, ?)")
                .params(room.id(), room.participant(), content, receivedAt).update();
        return jdbc.sql("SELECT id FROM chat_messages WHERE lecture_room_id = ? ORDER BY id DESC LIMIT 1")
                .param(room.id()).query(Long.class).single();
    }

    private long analysisId() {
        return jdbc.sql("SELECT id FROM ai_analyses WHERE lecture_room_id = ? AND window_start_at = ?")
                .params(room.id(), START).query(Long.class).single();
    }

    private void assertStatus(String status) {
        assertThat(jdbc.sql("SELECT status FROM ai_analyses WHERE id = ?").param(analysisId())
                .query(String.class).single()).isEqualTo(status);
    }

    private long count(String sql, long id) {
        return jdbc.sql(sql).param(id).query(Long.class).single();
    }

    private List<Long> linkedIds(long analysisId) {
        return jdbc.sql("SELECT link.chat_message_id FROM ai_topic_group_messages link "
                + "JOIN ai_topic_groups g ON g.id = link.topic_group_id WHERE g.analysis_id = ?")
                .param(analysisId).query(Long.class).list();
    }

    private record Room(long id, long participant) { }

    private static final class FailOnceInitializationBatchRepository extends AiAnalysisBatchRepository {
        private final AiAnalysisBatchRepository delegate;
        private final AtomicBoolean fail = new AtomicBoolean(true);

        private FailOnceInitializationBatchRepository(AiAnalysisBatchRepository delegate, JdbcClient jdbc,
                                                      ObjectMapper mapper) {
            super(jdbc, mapper);
            this.delegate = delegate;
        }

        @Override
        public Initialization initializeForProcessing(long analysisId, List<com.react.ai.dto.AudienceAnalysisBatch> pending) {
            if (fail.compareAndSet(true, false)) {
                throw new DataAccessResourceFailureException("temporary initialization failure");
            }
            return delegate.initializeForProcessing(analysisId, pending);
        }
    }

    private static class FakeLlm implements AudienceAnalysisLlmClient {
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean failAfterFirst;
        private volatile boolean blockFirst;

        @Override
        public AudienceAnalysisLlmResponse analyze(List<AudienceAnalysisInputMessage> input) {
            int number = calls.incrementAndGet();
            if (blockFirst && number == 1) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fake timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            if (failAfterFirst && number > 1) {
                throw new GeminiAudienceAnalysisException(GeminiAudienceAnalysisErrorCode.HTTP_5XX,
                        true, null, "fake 5xx");
            }
            String ids = input.stream().map(message -> Long.toString(message.messageId()))
                    .collect(java.util.stream.Collectors.joining(","));
            return new AudienceAnalysisLlmResponse("{\"topics\":[{\"topic\":\"질문\","
                    + "\"summary\":\"청중 질문이 나왔습니다.\",\"category\":\"QUESTION\","
                    + "\"sourceMessageIds\":[" + ids + "]}]}", "fake-model");
        }

        int calls() { return calls.get(); }
    }
}
