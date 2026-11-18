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
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.http.MediaType;

@SpringBootTest
@ActiveProfiles("test")
class AudienceAnalysisIntegrationTest {
    private static final String GEMINI_MODEL = "gemini-3.5-flash-lite";
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired AiAnalysisRepository analyses;
    @Autowired AudienceMessageRepository messages;

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

    private GeminiAudienceAnalysisLlmClient geminiClient(RestClient restClient) {
        return new GeminiAudienceAnalysisLlmClient(restClient, new ObjectMapper(),
                new GeminiAudienceAnalysisProperties("test-key", GEMINI_MODEL, "https://gemini.test/v1beta",
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 100, 32_000, 2_048, 128_000));
    }

    private AudienceAnalysisLlmResponse response(String content) {
        return new AudienceAnalysisLlmResponse(content, "fake-model");
    }

    private long insertAudienceMessage(String content, LocalDateTime receivedAt) {
        jdbc.sql("""
                INSERT INTO chat_messages(lecture_room_id, participant_id, content, received_at)
                VALUES (?, ?, ?, ?)
                """).params(roomId, participantId, content, receivedAt).update();
        return jdbc.sql("SELECT id FROM chat_messages WHERE lecture_room_id = ? ORDER BY id DESC LIMIT 1")
                .param(roomId).query(Long.class).single();
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
