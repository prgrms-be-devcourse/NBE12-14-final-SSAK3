package com.react.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.ai.dto.AudienceAnalysisLlmResponse;
import com.react.ai.repository.AiAnalysisFinalizationRepository;
import com.react.ai.repository.AiAnalysisFinalizationRepository.EndedRoom;
import com.react.ai.repository.AiAnalysisRepository;
import com.react.ai.repository.AudienceMessageRepository;
import com.react.report.dto.ReportReceipt;
import com.react.report.service.ReportGenerationException;
import com.react.report.service.ReportGenerationWorker;
import com.react.report.service.ReportOverviewClient;
import com.react.report.service.ReportQueryService;
import com.react.report.service.ReportRequestService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportGenerationOrderIntegrationTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 22, 10, 0);
    private static final String EMAIL = "report-order@example.com";

    @Autowired DataSource dataSource;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired ApplicationEventPublisher events;
    @Autowired AiAnalysisFinalizationRepository finalizations;
    @Autowired AiAnalysisRepository analyses;
    @Autowired AudienceMessageRepository messages;
    @Autowired AiAnalysisJsonParser parser;
    @Autowired ReportRequestService requests;
    @Autowired ReportGenerationWorker worker;
    @Autowired ReportQueryService query;
    @MockitoBean ReportOverviewClient overviewClient;
    @MockitoBean(name = "reportGenerationExecutor") Executor reportExecutor;

    private final Queue<Runnable> checks = new ConcurrentLinkedQueue<>();
    private Flyway flyway;
    private long host;
    private EndedRoom room;

    @BeforeAll
    void prepareIsolatedRecoveryDatabase() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).isEqualTo("react_test");
        // Startup recovery scans all pending and processing reports, including records from prior test runs.
        flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        host = insert("INSERT INTO users(email, password_hash, name, created_at) VALUES (?, 'hash', '강연자', NOW())", EMAIL);
    }

    @AfterAll
    void clearRecoveryDatabase() {
        if (flyway != null) flyway.clean();
    }

    @BeforeEach
    void prepareLecture() throws Exception {
        room = endedRoom();
        checks.clear();
        doAnswer(invocation -> checks.add(invocation.getArgument(0, Runnable.class)))
                .when(reportExecutor).execute(any());
        when(overviewClient.generate(any())).thenReturn(emptyDraft());
    }

    @Test
    void 접수_먼저면_분석_확정까지_대기하고_실패한_구간도_원문으로_생성한다() throws Exception {
        var receipt = requests.request(room.roomId(), EMAIL);
        checks.remove().run();
        assertThat(status(receipt.reportId())).isEqualTo("PENDING");
        verifyNoInteractions(overviewClient);

        finalizeAnalysis(room, "잘못된 분석 결과");
        checks.remove().run();
        var content = assertGenerated(receipt);
        assertThat(content.at("/timeline/0/chatMessages/0/text").asText()).isEqualTo("예시가 궁금해요");
        assertThat(content.at("/timeline/0/audienceOpinions/0/status").asText()).isEqualTo("FAILED");
        assertThat(content.at("/followUp/status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(content.at("/overview/mainInterests").isArray()).isTrue();
    }

    @Test
    void 같은_채팅에서_답이_확인된_질문만_제외하고_남은_질문을_같은_보고서에서_다시_읽는다() throws Exception {
        long messageId = jdbc.sql("SELECT id FROM chat_messages WHERE lecture_room_id = ?")
                .param(room.roomId()).query(Long.class).single();
        String original = "수수료는 어떻게 계산해요? 실패해도 내나요?";
        jdbc.sql("UPDATE chat_messages SET content = ? WHERE id = ?").params(original, messageId).update();
        long sessionId = insert("""
                INSERT INTO stt_sessions(lecture_room_id,status,started_at,ended_at) VALUES (?, 'STOPPED', ?, ?)
                """, room.roomId(), START, room.endedAt());
        long segmentId = insert("""
                INSERT INTO stt_segments(stt_session_id,segment_key,transcript,segment_start_at,segment_end_at,received_at)
                VALUES (?, ?, '사용한 가스에 단가를 곱합니다.', ?, ?, ?)
                """, sessionId, UUID.randomUUID().toString(), START.plusSeconds(5), START.plusSeconds(9), START.plusSeconds(10));
        when(overviewClient.generate(any())).thenReturn(mapper.readTree("""
                {"overview":{"mainInterests":[],"reviewPoints":[],"suggestions":[]},
                 "followUp":{"reviewedChatIds":[%1$d],"groups":[
                   {"title":"수수료 계산","questions":[{"requests":[{"messageId":%1$d,"quote":"수수료는 어떻게 계산해요?","reexplanation":false}],"answerSegmentIds":[%2$d]}],
                    "kind":"PREPARATION","text":"계산 예제를 준비하세요.","segmentIds":[]},
                   {"title":"실패한 거래의 수수료","questions":[{"requests":[{"messageId":%1$d,"quote":"실패해도 내나요?","reexplanation":false}],"answerSegmentIds":[]}],
                    "kind":"PREPARATION","text":"실패한 거래의 수수료 예시를 준비하세요.","segmentIds":[]}]}}
                """.formatted(messageId, segmentId)));

        var receipt = requests.request(room.roomId(), EMAIL);
        checks.remove().run();
        finalizeAnalysis(room, "{\"topics\":[]}");
        checks.remove().run();

        var report = query.detail(room.roomId(), EMAIL);
        assertThat(report.reportId()).isEqualTo(receipt.reportId());
        assertThat(report.status()).isEqualTo(ReportReceipt.Status.COMPLETED);
        var content = report.content();
        assertThat(content.at("/followUp/status").asText()).isEqualTo("CANDIDATES");
        assertThat(content.at("/followUp/groups").size()).isOne();
        var request = content.at("/followUp/groups/0/questions/0/requests/0");
        assertThat(request.path("messageId").asLong()).isEqualTo(messageId);
        assertThat(request.path("quote").asText()).isEqualTo("실패해도 내나요?");
        assertThat(content.at("/timeline/0/chatMessages/0/text").asText()).isEqualTo(original);
        assertThat(query.detail(room.roomId(), EMAIL)).isEqualTo(report);
        verify(overviewClient).generate(any());
    }

    @Test
    void 분석_먼저면_접수를_기다렸다가_같은_ID로_생성한다() throws Exception {
        finalizeAnalysis(room, "{\"topics\":[]}");
        checks.remove().run();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lecture_reports WHERE lecture_room_id = ?")
                .param(room.roomId()).query(Integer.class).single()).isZero();
        verifyNoInteractions(overviewClient);

        var receipt = requests.request(room.roomId(), EMAIL);
        checks.remove().run();
        assertGenerated(receipt);
    }

    @Test
    void 자동_접수와_소유자의_수동_접수가_겹쳐도_한_건만_접수한다() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CyclicBarrier(2);
            var auto = pool.submit(together(start, () -> requests.requestEndedRoom(room.roomId())));
            var manual = pool.submit(together(start, () -> requests.request(room.roomId(), EMAIL)));
            var receipt = auto.get(10, TimeUnit.SECONDS);
            assertThat(manual.get(10, TimeUnit.SECONDS)).isEqualTo(receipt);
            assertThat(checks).hasSize(1);

            checks.remove().run();
            assertThat(status(receipt.reportId())).isEqualTo("PENDING");
            verifyNoInteractions(overviewClient);
            finalizeAnalysis(room, "{\"topics\":[]}");
            checks.remove().run();
            assertGenerated(receipt);
        }
    }

    @Test
    void 접수와_분석_완료가_겹쳐_두_작업자가_동시에_확인해도_보고서를_한_번만_생성한다() throws Exception {
        var generating = new CountDownLatch(1);
        var continueGeneration = new CountDownLatch(1);
        var draft = emptyDraft();
        when(overviewClient.generate(any())).thenAnswer(invocation -> {
            generating.countDown();
            assertThat(continueGeneration.await(10, TimeUnit.SECONDS)).isTrue();
            return draft;
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var signals = new CyclicBarrier(2);
            var receipt = pool.submit(together(signals, () -> requests.request(room.roomId(), EMAIL)));
            var finished = pool.submit(together(signals, () -> {
                finalizeAnalysis(room, "{\"topics\":[]}");
                return null;
            }));
            var accepted = receipt.get(10, TimeUnit.SECONDS);
            finished.get(10, TimeUnit.SECONDS);
            assertThat(checks).hasSize(2);

            var workers = new CyclicBarrier(2);
            var first = pool.submit(together(workers, Executors.callable(checks.remove())));
            var second = pool.submit(together(workers, Executors.callable(checks.remove())));
            try {
                assertThat(generating.await(10, TimeUnit.SECONDS)).isTrue();
                worker.recover(); // another instance starts while the original worker is awaiting the model
                assertThat(status(accepted.reportId())).isEqualTo("PROCESSING");
            } finally {
                continueGeneration.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertGenerated(accepted);
        }
    }

    @Test
    void 서버가_재시작되면_대기_보고서만_같은_ID로_생성하고_중단된_처리는_재호출_없이_실패한다() throws Exception {
        var abandoned = requests.request(endedRoom().roomId(), EMAIL);
        jdbc.sql("UPDATE lecture_reports SET status = 'PROCESSING' WHERE id = ?").param(abandoned.reportId()).update();
        var pending = requests.request(room.roomId(), EMAIL);
        finalizeAnalysis(room, "{\"topics\":[]}");
        checks.clear(); // queued work is lost across a process restart

        worker.recover();

        assertGenerated(pending);
        assertThat(status(abandoned.reportId())).isEqualTo("FAILED");
        assertThat(requests.request(abandoned.roomId(), EMAIL).reportId()).isEqualTo(abandoned.reportId());
        assertThat(checks).isEmpty();
    }

    @Test
    void 강연_시간이_잘못되면_대기하지_않고_같은_ID를_실패로_저장한다() {
        jdbc.sql("UPDATE lecture_rooms SET ended_at = ? WHERE id = ?")
                .params(START.minusSeconds(1), room.roomId()).update();
        var receipt = requests.request(room.roomId(), EMAIL);
        checks.remove().run();
        assertThat(status(receipt.reportId())).isEqualTo("FAILED");
        verifyNoInteractions(overviewClient);
    }

    @Test
    void 보고서_모델_호출이_실패하면_재접수와_재시작에서도_재호출하지_않는다() {
        when(overviewClient.generate(any())).thenThrow(ReportGenerationException.requestFailed());
        var receipt = requests.request(room.roomId(), EMAIL);
        checks.remove().run();
        finalizeAnalysis(room, "{\"topics\":[]}");
        checks.remove().run();

        var saved = requests.request(room.roomId(), EMAIL);
        assertThat(saved.reportId()).isEqualTo(receipt.reportId());
        assertThat(saved.status().name()).isEqualTo("FAILED");
        assertThat(checks).isEmpty();
        worker.recover();
        verify(overviewClient).generate(any());
    }

    private JsonNode assertGenerated(ReportReceipt receipt) throws Exception {
        assertThat(receipt.status().name()).isEqualTo("PENDING");
        var existing = requests.request(room.roomId(), EMAIL);
        assertThat(existing.reportId()).isEqualTo(receipt.reportId());
        assertThat(existing.status().name()).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT id FROM lecture_reports WHERE lecture_room_id = ?")
                .param(room.roomId()).query(Long.class).list()).containsExactly(receipt.reportId());
        assertThat(checks).isEmpty();
        verify(overviewClient).generate(any());
        return mapper.readTree(jdbc.sql("SELECT content FROM lecture_reports WHERE id = ?")
                .param(receipt.reportId()).query(String.class).single());
    }

    private String status(long reportId) {
        return jdbc.sql("SELECT status FROM lecture_reports WHERE id = ?").param(reportId).query(String.class).single();
    }

    private JsonNode emptyDraft() throws Exception {
        return mapper.readTree("""
                {"overview":{"mainInterests":[],"reviewPoints":[],"suggestions":[]},
                 "followUp":{"reviewedChatIds":[],"groups":[]}}
                """);
    }

    private void finalizeAnalysis(EndedRoom lecture, String topicResponse) {
        var audience = new AudienceAnalysisService(analyses, messages,
                candidates -> new AudienceAnalysisLlmResponse(topicResponse, "test"), parser);
        var finalizer = new AiAnalysisFinalizationService(finalizations, analyses, audience,
                new AiAnalysisWindowCalculator(), events);
        finalizer.processRoom(lecture, 1, Duration.ofSeconds(90));
    }

    private EndedRoom endedRoom() {
        var end = START.plusSeconds(15);
        long roomId = insert("""
                INSERT INTO lecture_rooms(host_user_id, title, join_code, status, created_at, started_at, ended_at)
                VALUES (?, '보고서 생성 순서', ?, 'ENDED', ?, ?, ?)
                """, host, UUID.randomUUID().toString(), START, START, end);
        long participant = insert("""
                INSERT INTO lecture_participants(lecture_room_id, nickname, participant_token_hash, joined_at)
                VALUES (?, '청중', ?, ?)
                """, roomId, UUID.randomUUID().toString(), START);
        jdbc.sql("INSERT INTO chat_messages(lecture_room_id, participant_id, content, received_at) VALUES (?, ?, ?, ?)")
                .params(roomId, participant, "예시가 궁금해요", START.plusSeconds(1)).update();
        return new EndedRoom(roomId, START, end);
    }

    private long insert(String sql, Object... params) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql(sql).params(params).update(keys);
        return keys.getKey().longValue();
    }

    private static <T> Callable<T> together(CyclicBarrier barrier, Callable<T> work) {
        return () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return work.call();
        };
    }
}
