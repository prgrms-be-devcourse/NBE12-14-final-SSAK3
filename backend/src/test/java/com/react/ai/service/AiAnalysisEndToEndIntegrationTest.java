package com.react.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.ai.config.AiAnalysisSchedulerProperties;
import com.react.ai.config.AudienceAnalysisCostProperties;
import com.react.ai.config.AudienceAnalysisRetryProperties;
import com.react.ai.domain.AiAnalysisFinalizationStatus;
import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.dto.AudienceAnalysisLlmResponse;
import com.react.ai.dto.AudienceMessage;
import com.react.ai.exception.GeminiAudienceAnalysisErrorCode;
import com.react.ai.exception.GeminiAudienceAnalysisException;
import com.react.ai.repository.AiAnalysisFinalizationRepository;
import com.react.ai.repository.AiAnalysisRepository;
import com.react.ai.repository.AiAnalysisTargetRoomRepository;
import com.react.ai.repository.AiLlmCallUsageRepository;
import com.react.ai.repository.AudienceMessageRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 API가 아닌, 이미 저장된 강연방·청중 채팅을 AI 스케줄러가 처리하는 경로를 검증한다.
 * 실제 Gemini 대신 결정적인 Fake LLM을 사용한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class AiAnalysisEndToEndIntegrationTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime LIVE_START = LocalDateTime.of(2000, 1, 1, 10, 0);
    private static final LocalDateTime FINALIZATION_START = LocalDateTime.of(2001, 1, 1, 10, 0);

    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired AiAnalysisRepository analyses;
    @Autowired AiAnalysisTargetRoomRepository targetRooms;
    @Autowired AiAnalysisFinalizationRepository finalizations;
    @Autowired AudienceMessageRepository messages;
    @Autowired AiLlmCallUsageRepository usages;

    @BeforeEach
    void migrateTestDatabase() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @Test
    void LIVE_정규_구간을_스케줄러가_처리하고_빈_구간과_강연방_격리를_보장한다() {
        TestRoom firstRoom = insertRoom("LIVE", LIVE_START, null);
        TestRoom secondRoom = insertRoom("LIVE", LIVE_START, null);
        long firstMessage = insertAudienceMessage(firstRoom, "Redis 도입 이유가 궁금합니다.", LIVE_START.plusSeconds(5));
        long secondMessage = insertAudienceMessage(firstRoom, "MySQL만으로 가능한지도 설명해 주세요.", LIVE_START.plusSeconds(20));
        long otherRoomMessage = insertAudienceMessage(secondRoom, "다른 강연방의 질문입니다.", LIVE_START.plusSeconds(10));

        FakeAudienceAnalysisLlm fake = new FakeAudienceAnalysisLlm();
        scheduler(audienceService(fake), LIVE_START.plusSeconds(65)).processCompletedRegularWindows();

        long firstAnalysis = analysisId(firstRoom.roomId(), LIVE_START, LIVE_START.plusSeconds(30));
        long emptyAnalysis = analysisId(firstRoom.roomId(), LIVE_START.plusSeconds(30), LIVE_START.plusSeconds(60));
        long secondRoomAnalysis = analysisId(secondRoom.roomId(), LIVE_START, LIVE_START.plusSeconds(30));

        assertAnalysisStatus(firstAnalysis, "COMPLETED");
        assertThat(topicGroupMessageIds(firstAnalysis)).containsExactlyInAnyOrder(firstMessage, secondMessage);
        assertAnalysisStatus(emptyAnalysis, "COMPLETED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_topic_groups WHERE analysis_id = ?")
                .param(emptyAnalysis).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_analyses WHERE id = ? AND model_name IS NULL")
                .param(emptyAnalysis).query(Long.class).single()).isEqualTo(1L);

        assertAnalysisStatus(secondRoomAnalysis, "COMPLETED");
        assertThat(topicGroupMessageIds(secondRoomAnalysis)).containsExactly(otherRoomMessage);
        assertThat(jdbc.sql("""
                SELECT COUNT(*)
                FROM ai_topic_group_messages link_row
                JOIN ai_topic_groups topic_group ON topic_group.id = link_row.topic_group_id
                JOIN chat_messages message ON message.id = link_row.chat_message_id
                WHERE topic_group.analysis_id = ? AND message.lecture_room_id <> ?
                """).params(firstAnalysis, firstRoom.roomId()).query(Long.class).single()).isZero();
        assertThat(fake.calls()).isEqualTo(2);
    }

    @Test
    void LIVE_스케줄러_경로에서_429_후_재시도해_한_분석과_두_호출_이력을_남긴다() {
        TestRoom room = insertRoom("LIVE", LIVE_START, null);
        long messageId = insertAudienceMessage(room, "재시도할 청중 질문", LIVE_START.plusSeconds(5));
        FakeAudienceAnalysisLlm fake = new FakeAudienceAnalysisLlm();
        fake.rateLimitOnce(room.roomId());

        scheduler(audienceService(fake), LIVE_START.plusSeconds(31)).processCompletedRegularWindows();

        long analysisId = analysisId(room.roomId(), LIVE_START, LIVE_START.plusSeconds(30));
        assertAnalysisStatus(analysisId, "COMPLETED");
        assertThat(fake.callsFor(room.roomId())).isEqualTo(2);
        assertThat(topicGroupMessageIds(analysisId)).containsExactly(messageId);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_topic_groups WHERE analysis_id = ?")
                .param(analysisId).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_topic_group_messages link_row "
                        + "JOIN ai_topic_groups topic_group ON topic_group.id = link_row.topic_group_id "
                        + "WHERE topic_group.analysis_id = ?")
                .param(analysisId).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("""
                SELECT call_status FROM ai_llm_call_usages
                WHERE analysis_id = ? ORDER BY attempt_number
                """).param(analysisId).query(String.class).list()).containsExactly("FAILED", "SUCCESS");
    }

    @Test
    void ENDED_강연은_남은_정규_구간과_잔여_구간을_처리하고_FAILED도_종결한다() {
        LocalDateTime endedAt = FINALIZATION_START.plusSeconds(75);
        TestRoom room = insertRoom("ENDED", FINALIZATION_START, endedAt);
        completeEmptyAnalysis(room.roomId(), FINALIZATION_START, FINALIZATION_START.plusSeconds(30));
        long completedMessage = insertAudienceMessage(room, "남은 정규 구간 질문", FINALIZATION_START.plusSeconds(35));
        insertAudienceMessage(room, "FINALIZATION_FAIL", FINALIZATION_START.plusSeconds(65));

        FakeAudienceAnalysisLlm fake = new FakeAudienceAnalysisLlm();
        AiAnalysisFinalizationService service = new AiAnalysisFinalizationService(finalizations, analyses,
                audienceService(fake), new AiAnalysisWindowCalculator());
        AiAnalysisFinalizationRepository.EndedRoom endedRoom = new AiAnalysisFinalizationRepository.EndedRoom(
                room.roomId(), FINALIZATION_START, endedAt);

        service.processRoom(endedRoom, 3, Duration.ofSeconds(90));

        long completedAnalysis = analysisId(room.roomId(), FINALIZATION_START.plusSeconds(30),
                FINALIZATION_START.plusSeconds(60));
        long failedResidualAnalysis = analysisId(room.roomId(), FINALIZATION_START.plusSeconds(60), endedAt);
        assertAnalysisStatus(completedAnalysis, "COMPLETED");
        assertThat(topicGroupMessageIds(completedAnalysis)).containsExactly(completedMessage);
        assertAnalysisStatus(failedResidualAnalysis, "FAILED");
        var finalization = finalizations.find(room.roomId());
        assertThat(finalization.status()).isEqualTo(AiAnalysisFinalizationStatus.COMPLETED);
        assertThat(finalization.failedAnalysisCount()).isEqualTo(1);
        assertThat(finalization.completedAt()).isNotNull();

        int callsBeforeRepeat = fake.calls();
        service.processRoom(endedRoom, 3, Duration.ofSeconds(90));
        assertThat(fake.calls()).isEqualTo(callsBeforeRepeat);
    }

    private AudienceAnalysisScheduler scheduler(AudienceAnalysisService audience, LocalDateTime now) {
        Clock clock = Clock.fixed(now.atZone(SEOUL).toInstant(), SEOUL);
        return new AudienceAnalysisScheduler(targetRooms, analyses, audience, new AiAnalysisWindowCalculator(), clock,
                new AiAnalysisSchedulerProperties(3));
    }

    private AudienceAnalysisService audienceService(FakeAudienceAnalysisLlm fake) {
        return new AudienceAnalysisService(analyses, messages, fake, new AiAnalysisJsonParser(new ObjectMapper()),
                new AudienceAnalysisRetryProperties(1, Duration.ZERO, Duration.ofSeconds(5)), usages,
                new AudienceAnalysisCostProperties("0.25", "1.50"));
    }

    private TestRoom insertRoom(String status, LocalDateTime startedAt, LocalDateTime endedAt) {
        String key = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO users(email, password_hash, name, created_at) VALUES (?, 'hash', 'host', NOW())")
                .param(key + "@example.com").update();
        long hostId = jdbc.sql("SELECT id FROM users WHERE email = ?").param(key + "@example.com")
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO lecture_rooms(host_user_id, title, join_code, status, created_at, started_at, ended_at)
                VALUES (?, 'AI 통합 테스트', ?, ?, NOW(), ?, ?)
                """).params(hostId, key, status, startedAt, endedAt).update();
        long roomId = jdbc.sql("SELECT id FROM lecture_rooms WHERE join_code = ?").param(key)
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO lecture_participants(lecture_room_id, nickname, participant_token_hash, joined_at)
                VALUES (?, '청중', ?, NOW())
                """).params(roomId, key + "-participant").update();
        long participantId = jdbc.sql("SELECT id FROM lecture_participants WHERE participant_token_hash = ?")
                .param(key + "-participant").query(Long.class).single();
        return new TestRoom(roomId, participantId);
    }

    private long insertAudienceMessage(TestRoom room, String content, LocalDateTime receivedAt) {
        jdbc.sql("""
                INSERT INTO chat_messages(lecture_room_id, participant_id, content, received_at)
                VALUES (?, ?, ?, ?)
                """).params(room.roomId(), room.participantId(), content, receivedAt).update();
        return jdbc.sql("SELECT id FROM chat_messages WHERE lecture_room_id = ? AND content = ? ORDER BY id DESC LIMIT 1")
                .params(room.roomId(), content).query(Long.class).single();
    }

    private void completeEmptyAnalysis(long roomId, LocalDateTime startAt, LocalDateTime endAt) {
        var acquired = analyses.acquire(roomId, startAt, endAt);
        assertThat(analyses.markProcessing(acquired.analysis().id())).isTrue();
        assertThat(analyses.completeEmpty(acquired.analysis().id())).isTrue();
    }

    private long analysisId(long roomId, LocalDateTime startAt, LocalDateTime endAt) {
        return jdbc.sql("""
                SELECT id FROM ai_analyses
                WHERE lecture_room_id = ? AND window_start_at = ? AND window_end_at = ?
                """).params(roomId, startAt, endAt).query(Long.class).single();
    }

    private Set<Long> topicGroupMessageIds(long analysisId) {
        return new HashSet<>(jdbc.sql("""
                SELECT link_row.chat_message_id
                FROM ai_topic_group_messages link_row
                JOIN ai_topic_groups topic_group ON topic_group.id = link_row.topic_group_id
                WHERE topic_group.analysis_id = ?
                """).param(analysisId).query(Long.class).list());
    }

    private void assertAnalysisStatus(long analysisId, String status) {
        assertThat(jdbc.sql("SELECT status FROM ai_analyses WHERE id = ?").param(analysisId)
                .query(String.class).single()).isEqualTo(status);
    }

    private record TestRoom(long roomId, long participantId) {
    }

    private static final class FakeAudienceAnalysisLlm implements AudienceAnalysisLlmClient {
        private final AtomicInteger calls = new AtomicInteger();
        private final Map<Long, AtomicInteger> callsByRoom = new HashMap<>();
        private final Set<Long> rateLimitOnceRooms = new HashSet<>();

        void rateLimitOnce(long roomId) {
            rateLimitOnceRooms.add(roomId);
        }

        @Override
        public AudienceAnalysisLlmResponse analyze(java.util.List<AudienceMessage> candidates) {
            long roomId = candidates.getFirst().roomId();
            int roomCalls = callsByRoom.computeIfAbsent(roomId, ignored -> new AtomicInteger()).incrementAndGet();
            calls.incrementAndGet();
            if (rateLimitOnceRooms.contains(roomId) && roomCalls == 1) {
                throw new GeminiAudienceAnalysisException(GeminiAudienceAnalysisErrorCode.RATE_LIMIT, true,
                        Duration.ZERO, "fake rate limit");
            }
            if (candidates.stream().anyMatch(message -> "FINALIZATION_FAIL".equals(message.content()))) {
                throw new GeminiAudienceAnalysisException(GeminiAudienceAnalysisErrorCode.INVALID_RESPONSE, false,
                        null, "fake invalid response");
            }
            String sourceIds = candidates.stream().map(AudienceMessage::messageId)
                    .map(String::valueOf).collect(Collectors.joining(","));
            return new AudienceAnalysisLlmResponse("""
                    {"topics":[{"topic":"테스트 주제","summary":"청중 메시지를 요약했습니다.",
                    "category":"MIXED","sourceMessageIds":[%s]}]}
                    """.formatted(sourceIds), modelName(), new AudienceAnalysisLlmResponse.Usage(10L, 5L, 15L));
        }

        @Override
        public String modelName() {
            return "fake-gemini";
        }

        int calls() {
            return calls.get();
        }

        int callsFor(long roomId) {
            AtomicInteger count = callsByRoom.get(roomId);
            return count == null ? 0 : count.get();
        }
    }
}
