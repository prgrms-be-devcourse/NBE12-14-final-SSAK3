package com.react.ai.service;

import com.react.ai.config.AiAnalysisSchedulerProperties;
import com.react.ai.config.AudienceAnalysisCostProperties;
import com.react.ai.config.AudienceAnalysisRetryProperties;
import com.react.ai.config.GeminiAudienceAnalysisProperties;
import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.dto.AudienceAnalysisInputMessage;
import com.react.ai.dto.AudienceAnalysisLlmResponse;
import com.react.ai.event.AiAnalysisCompletedDomainEvent;
import com.react.ai.repository.AiAnalysisBatchAttemptRepository;
import com.react.ai.repository.AiAnalysisBatchRepository;
import com.react.ai.repository.AiAnalysisRepository;
import com.react.ai.repository.AiAnalysisTargetRoomRepository;
import com.react.ai.repository.AiLlmCallUsageRepository;
import com.react.ai.repository.AudienceMessageRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 실제 MySQL과 LIVE 스케줄러 경로를 이용하되, 외부 Gemini는 호출하지 않는 opt-in 성능 시나리오다.
 * 실행: RUN_AI_PERFORMANCE_SCENARIO=true ./gradlew test --rerun-tasks --tests
 * com.react.ai.service.AudienceAnalysisPerformanceScenarioTest
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_AI_PERFORMANCE_SCENARIO", matches = "true")
class AudienceAnalysisPerformanceScenarioTest {
    private static final LocalDateTime START = LocalDateTime.of(2002, 1, 2, 10, 0);
    private static final Duration ANALYSIS_WINDOW = Duration.ofSeconds(30);
    private static final Duration THIRTY_SECONDS = Duration.ofSeconds(30);
    private static final AiAnalysisSchedulerProperties SCHEDULER = new AiAnalysisSchedulerProperties(3);
    private static final AudienceAnalysisRetryProperties RETRY =
            new AudienceAnalysisRetryProperties(1, Duration.ZERO, Duration.ofSeconds(5));
    private static final AudienceAnalysisCostProperties COST = new AudienceAnalysisCostProperties(null, null);

    @Autowired JdbcClient jdbc;
    @Autowired AiAnalysisRepository analyses;
    @Autowired AiAnalysisBatchRepository batches;
    @Autowired AiAnalysisBatchAttemptRepository attempts;
    @Autowired AudienceMessageRepository messages;
    @Autowired AudienceMessagePreprocessor preprocessor;
    @Autowired AudienceAnalysisBatchSplitter splitter;
    @Autowired AudienceAnalysisBatchInputRestorer restorer;
    @Autowired AiAnalysisJsonParser parser;
    @Autowired AiLlmCallUsageRepository usages;

    @BeforeEach
    void verifyIsolatedTestDatabase() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).isEqualTo("react_test");
    }

    @Test
    void LIVE_강연방_3개와_방당_채팅_100개를_순차_분석한다() {
        skipWhenOperatingScaleOnly();
        ScenarioResult result = runScenario("three-rooms-one-window", 3, 1, 100);

        assertScenarioCompleted(result, 3, 1, 100);
    }

    @Test
    void LIVE_강연방_5개와_방당_채팅_100개를_순차_분석한다() {
        skipWhenOperatingScaleOnly();
        ScenarioResult result = runScenario("five-rooms-one-window", 5, 1, 100);

        assertScenarioCompleted(result, 5, 1, 100);
    }

    /**
     * 첫 실행이 한 주기 늦었다고 가정해 방마다 두 정규 구간이 누적된 경우를 확인한다.
     * maxWindowsPerRun=3 이므로 이번 시나리오에서는 두 구간 모두 같은 LIVE 실행에서 처리된다.
     */
    @Test
    void LIVE_강연방_3개에서_두_정규_구간이_누적돼도_상태가_수렴한다() {
        skipWhenOperatingScaleOnly();
        ScenarioResult result = runScenario("three-rooms-two-windows", 3, 2, 100);

        assertScenarioCompleted(result, 3, 2, 100);
    }

    @Test
    void 운영규모_6개방_방당_채팅_10개_한구간을_순차_분석한다() {
        assertScenarioCompleted(runScenario("six-rooms-ten-messages-one-window", 6, 1, 10), 6, 1, 10);
    }

    @Test
    void 운영규모_6개방_방당_채팅_50개_한구간을_순차_분석한다() {
        assertScenarioCompleted(runScenario("six-rooms-fifty-messages-one-window", 6, 1, 50), 6, 1, 50);
    }

    @Test
    void 운영규모_6개방_방당_채팅_100개_한구간을_순차_분석한다() {
        assertScenarioCompleted(runScenario("six-rooms-one-hundred-messages-one-window", 6, 1, 100), 6, 1, 100);
    }

    @Test
    void 운영규모_6개방_방당_채팅_200개_한구간을_배치로_순차_분석한다() {
        assertScenarioCompleted(runScenario("six-rooms-two-hundred-messages-one-window", 6, 1, 200), 6, 1, 200);
    }

    @Test
    void 운영규모_6개방_방당_채팅_100개_두구간의_적체를_확인한다() {
        assertScenarioCompleted(runScenario("six-rooms-one-hundred-messages-two-windows", 6, 2, 100), 6, 2, 100);
    }

    private ScenarioResult runScenario(String name, int roomCount, int completedWindows, int messagesPerRoomPerWindow) {
        List<Room> rooms = new ArrayList<>();
        for (int roomIndex = 0; roomIndex < roomCount; roomIndex++) {
            Room room = insertLiveRoom();
            rooms.add(room);
            insertMessages(room, roomIndex, completedWindows, messagesPerRoomPerWindow);
        }

        LocalDateTime now = START.plus(ANALYSIS_WINDOW.multipliedBy(completedWindows)).plusSeconds(1);
        FixedDelayFakeLlm llm = new FixedDelayFakeLlm(fakeDelay());
        Map<Long, Long> completionNanos = new ConcurrentHashMap<>();
        AudienceAnalysisService service = service(llm, completionNanos);
        AiAnalysisTargetRoomRepository liveRooms = mock(AiAnalysisTargetRoomRepository.class);
        when(liveRooms.findLiveRoomsWithStartedAt()).thenReturn(rooms.stream()
                .map(room -> new AiAnalysisTargetRoomRepository.LiveRoom(room.id(), START)).toList());
        Clock clock = Clock.fixed(now.atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"));
        AudienceAnalysisScheduler scheduler = new AudienceAnalysisScheduler(liveRooms, analyses, service,
                new AiAnalysisWindowCalculator(), clock, SCHEDULER,
                new AiAnalysisSchedulerMetrics(new SimpleMeterRegistry()));

        long startedNanos = System.nanoTime();
        scheduler.processCompletedRegularWindows();
        long elapsedNanos = System.nanoTime() - startedNanos;

        List<RoomResult> roomResults = rooms.stream()
                .map(room -> roomResult(room, completionNanos, startedNanos, completedWindows))
                .sorted(Comparator.comparingLong(RoomResult::roomId))
                .toList();
        ScenarioResult result = new ScenarioResult(name, roomCount, completedWindows, messagesPerRoomPerWindow,
                llm.delay(), elapsedNanos, llm.calls(), roomResults);
        print(result);
        return result;
    }

    private AudienceAnalysisService service(FixedDelayFakeLlm llm, Map<Long, Long> completionNanos) {
        ApplicationEventPublisher events = event -> {
            if (event instanceof AiAnalysisCompletedDomainEvent completed) {
                completionNanos.put(completed.analysisId(), System.nanoTime());
            }
        };
        AiAnalysisCompletionService completions = new AiAnalysisCompletionService(analyses, events);
        AudienceAnalysisBatchService batchService = new AudienceAnalysisBatchService(analyses, batches, attempts,
                restorer, llm, parser, usages, RETRY, COST);
        AudienceAnalysisBatchCoordinator coordinator = new AudienceAnalysisBatchCoordinator(analyses, batches,
                splitter, batchService, restorer, parser, completions,
                new GeminiAudienceAnalysisProperties("", "performance-fake", "", null, null,
                        100, 32_000, 2_048, 128_000), SCHEDULER);
        return new AudienceAnalysisService(analyses, messages, llm, parser, RETRY, usages, COST,
                completions, preprocessor, coordinator);
    }

    private RoomResult roomResult(Room room, Map<Long, Long> completionNanos, long startedNanos,
                                  int completedWindows) {
        List<Long> analysisIds = jdbc.sql("""
                SELECT id FROM ai_analyses
                WHERE lecture_room_id = ? AND window_start_at >= ? AND window_start_at < ?
                ORDER BY window_start_at
                """).params(room.id(), START, START.plus(ANALYSIS_WINDOW.multipliedBy(completedWindows)))
                .query(Long.class).list();
        Map<AiAnalysisStatus, Integer> statuses = new EnumMap<>(AiAnalysisStatus.class);
        int batchCount = 0;
        long latestCompletionNanos = -1;
        for (long analysisId : analysisIds) {
            String statusName = jdbc.sql("SELECT status FROM ai_analyses WHERE id = ?")
                    .param(analysisId).query(String.class).single();
            AiAnalysisStatus status = AiAnalysisStatus.valueOf(statusName);
            statuses.merge(status, 1, Integer::sum);
            batchCount += jdbc.sql("SELECT COUNT(*) FROM ai_analysis_batches WHERE analysis_id = ?")
                    .param(analysisId).query(Integer.class).single();
            Long completedAtNanos = completionNanos.get(analysisId);
            if (completedAtNanos != null) {
                latestCompletionNanos = Math.max(latestCompletionNanos, completedAtNanos);
            }
        }
        long completionElapsedNanos = latestCompletionNanos < 0 ? -1 : latestCompletionNanos - startedNanos;
        return new RoomResult(room.id(), statuses.getOrDefault(AiAnalysisStatus.COMPLETED, 0),
                statuses.getOrDefault(AiAnalysisStatus.FAILED, 0),
                statuses.getOrDefault(AiAnalysisStatus.PENDING, 0),
                statuses.getOrDefault(AiAnalysisStatus.PROCESSING, 0), batchCount, completionElapsedNanos);
    }

    private void assertScenarioCompleted(ScenarioResult result, int expectedRooms, int expectedWindows,
                                         int messagesPerRoomPerWindow) {
        assertThat(result.roomResults()).hasSize(expectedRooms);
        int batchesPerAnalysis = (int) Math.ceil(messagesPerRoomPerWindow / 100.0);
        assertThat(result.calls()).isEqualTo(expectedRooms * expectedWindows * batchesPerAnalysis);
        assertThat(result.completed()).isEqualTo(expectedRooms * expectedWindows);
        assertThat(result.failed()).isZero();
        assertThat(result.pending() + result.processing()).isZero();
        assertThat(result.batchCount()).isEqualTo(messagesPerRoomPerWindow > 100
                ? expectedRooms * expectedWindows * batchesPerAnalysis : 0);
    }

    private Room insertLiveRoom() {
        String key = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO users(email, password_hash, name, created_at) VALUES (?, 'hash', 'performance-host', NOW())")
                .param(key + "@example.com").update();
        long hostId = jdbc.sql("SELECT id FROM users WHERE email = ?").param(key + "@example.com")
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO lecture_rooms(host_user_id, title, join_code, status, created_at, started_at)
                VALUES (?, 'AI 성능 시나리오', ?, 'LIVE', NOW(), ?)
                """).params(hostId, key, START).update();
        long roomId = jdbc.sql("SELECT id FROM lecture_rooms WHERE join_code = ?").param(key)
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO lecture_participants(lecture_room_id, nickname, participant_token_hash, joined_at)
                VALUES (?, '성능 청중', ?, NOW())
                """).params(roomId, key + "-participant").update();
        long participantId = jdbc.sql("SELECT id FROM lecture_participants WHERE participant_token_hash = ?")
                .param(key + "-participant").query(Long.class).single();
        return new Room(roomId, participantId);
    }

    private void insertMessages(Room room, int roomIndex, int completedWindows, int messagesPerRoomPerWindow) {
        for (int window = 0; window < completedWindows; window++) {
            LocalDateTime receivedAt = START.plus(ANALYSIS_WINDOW.multipliedBy(window)).plusSeconds(1);
            for (int message = 0; message < messagesPerRoomPerWindow; message++) {
                jdbc.sql("""
                        INSERT INTO chat_messages(lecture_room_id, participant_id, content, received_at)
                        VALUES (?, ?, ?, ?)
                        """).params(room.id(), room.participantId(),
                        "성능 검증 질문 room=" + roomIndex + " window=" + window + " message=" + message + "?",
                        receivedAt).update();
            }
        }
    }

    private Duration fakeDelay() {
        String value = System.getenv("AI_PERFORMANCE_FAKE_DELAY_MS");
        if (value == null || value.isBlank()) {
            return Duration.ofSeconds(1);
        }
        try {
            long millis = Long.parseLong(value);
            if (millis < 0 || millis > 10_000) {
                throw new IllegalArgumentException();
            }
            return Duration.ofMillis(millis);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("AI_PERFORMANCE_FAKE_DELAY_MS는 0~10000 범위의 정수여야 합니다.");
        }
    }

    private void skipWhenOperatingScaleOnly() {
        Assumptions.assumeFalse("true".equalsIgnoreCase(System.getenv("AI_PERFORMANCE_OPERATING_SCALE_ONLY")),
                "운영 규모 시나리오만 실행하도록 요청되었습니다.");
    }

    private void print(ScenarioResult result) {
        System.out.printf("""

                [AI 성능 시나리오] %s
                rooms=%d, messagesPerRoomPerWindow=%d, completedWindowsPerRoom=%d, fakeLlmDelayMs=%d
                schedulerElapsedMs=%d, calls=%d, completed=%d, failed=%d, pending=%d, processing=%d, batches=%d
                schedulerQueueWaitMs=0 (테스트가 public 메서드를 직접 호출하므로 실제 @Scheduled fixedDelay 대기는 포함하지 않음)
                30초 초과: scheduler=%s, room=%s
                """, result.name(), result.roomCount(), result.messagesPerRoomPerWindow(), result.completedWindowsPerRoom(), result.fakeDelay().toMillis(),
                result.schedulerElapsedMillis(), result.calls(), result.completed(), result.failed(), result.pending(),
                result.processing(), result.batchCount(), result.schedulerExceededThirtySeconds(),
                result.anyRoomExceededThirtySeconds());
        for (RoomResult room : result.roomResults()) {
            System.out.printf("roomId=%d, completionMs=%d, completed=%d, failed=%d, pending=%d, processing=%d, batches=%d%n",
                    room.roomId(), room.completionElapsedMillis(), room.completed(), room.failed(), room.pending(),
                    room.processing(), room.batchCount());
        }
    }

    private record Room(long id, long participantId) {
    }

    private record RoomResult(long roomId, int completed, int failed, int pending, int processing, int batchCount,
                              long completionElapsedNanos) {
        long completionElapsedMillis() {
            return completionElapsedNanos < 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(completionElapsedNanos);
        }

        boolean exceededThirtySeconds() {
            return completionElapsedNanos > THIRTY_SECONDS.toNanos();
        }
    }

    private record ScenarioResult(String name, int roomCount, int completedWindowsPerRoom,
                                  int messagesPerRoomPerWindow, Duration fakeDelay,
                                  long schedulerElapsedNanos, int calls, List<RoomResult> roomResults) {
        long schedulerElapsedMillis() {
            return TimeUnit.NANOSECONDS.toMillis(schedulerElapsedNanos);
        }

        int completed() {
            return roomResults.stream().mapToInt(RoomResult::completed).sum();
        }

        int failed() {
            return roomResults.stream().mapToInt(RoomResult::failed).sum();
        }

        int pending() {
            return roomResults.stream().mapToInt(RoomResult::pending).sum();
        }

        int processing() {
            return roomResults.stream().mapToInt(RoomResult::processing).sum();
        }

        int batchCount() {
            return roomResults.stream().mapToInt(RoomResult::batchCount).sum();
        }

        boolean schedulerExceededThirtySeconds() {
            return schedulerElapsedNanos > THIRTY_SECONDS.toNanos();
        }

        boolean anyRoomExceededThirtySeconds() {
            return roomResults.stream().anyMatch(RoomResult::exceededThirtySeconds);
        }
    }

    private static final class FixedDelayFakeLlm implements AudienceAnalysisLlmClient {
        private final Duration delay;
        private final AtomicInteger calls = new AtomicInteger();

        private FixedDelayFakeLlm(Duration delay) {
            this.delay = delay;
        }

        @Override
        public AudienceAnalysisLlmResponse analyze(List<AudienceAnalysisInputMessage> input) {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("성능 Fake LLM 호출이 중단되었습니다.", e);
            }
            calls.incrementAndGet();
            String ids = input.stream().map(message -> Long.toString(message.messageId()))
                    .collect(java.util.stream.Collectors.joining(","));
            return new AudienceAnalysisLlmResponse("""
                    {"topics":[{"topic":"성능 테스트 질문","summary":"성능 테스트용 질문입니다.",
                    "category":"QUESTION","sourceMessageIds":[%s]}]}
                    """.formatted(ids), "performance-fake");
        }

        @Override
        public String modelName() {
            return "performance-fake";
        }

        private Duration delay() {
            return delay;
        }

        private int calls() {
            return calls.get();
        }
    }
}
