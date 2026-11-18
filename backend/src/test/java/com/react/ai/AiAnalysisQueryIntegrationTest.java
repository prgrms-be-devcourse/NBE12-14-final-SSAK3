package com.react.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiAnalysisQueryIntegrationTest {
    private static final String PASSWORD = "AiQueryTestPassword123!";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired PasswordEncoder passwordEncoder;

    private String ownerEmail;
    private String otherEmail;
    private long roomId;
    private long emptyRoomId;
    private long otherRoomId;
    private long topicGroupId;
    private long emptyTopicGroupId;
    private long otherTopicGroupId;

    @BeforeEach
    void prepareDatabase() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();

        String key = UUID.randomUUID().toString();
        ownerEmail = key + "@example.com";
        otherEmail = "other-" + key + "@example.com";
        long ownerId = insertUser(ownerEmail, "hash");
        long otherId = insertUser(otherEmail, "hash");
        roomId = insertRoom(ownerId, key + "-room", "ENDED");
        emptyRoomId = insertRoom(ownerId, key + "-empty", "LIVE");
        otherRoomId = insertRoom(otherId, key + "-other", "LIVE");
        long participantId = insertParticipant(roomId, key + "-participant", "청중A");

        LocalDateTime start = LocalDateTime.of(2026, 11, 17, 10, 0);
        long completed = insertAnalysis(roomId, start, "COMPLETED", start.plusSeconds(40), null);
        topicGroupId = insertTopicGroup(completed, "Redis 사용 이유", "MIXED", "도입 이유와 사례를 궁금해했습니다.");
        long firstMessage = insertMessage(roomId, participantId, "Redis를 꼭 사용해야 하나요?", start.plusSeconds(10));
        long secondMessage = insertMessage(roomId, participantId, "MySQL만으로 처리하는 예시도 보여 주세요.", start.plusSeconds(20));
        link(topicGroupId, firstMessage);
        link(topicGroupId, secondMessage);
        emptyTopicGroupId = insertTopicGroup(completed, "연결 원문 없음", "OPINION", "연결된 원문이 없는 테스트 그룹입니다.");

        insertAnalysis(roomId, start.plusSeconds(30), "FAILED", start.plusSeconds(50), "INTERNAL_ONLY");
        insertAnalysis(roomId, start.plusSeconds(60), "PENDING", null, null);
        insertAnalysis(roomId, start.plusSeconds(90), "PROCESSING", null, null);
        insertAnalysis(roomId, start.plusSeconds(120), "COMPLETED", start.plusSeconds(160), null);

        long otherParticipant = insertParticipant(otherRoomId, key + "-other-participant", "청중B");
        long otherAnalysis = insertAnalysis(otherRoomId, start, "COMPLETED", start.plusSeconds(40), null);
        otherTopicGroupId = insertTopicGroup(otherAnalysis, "다른 강연 주제", "QUESTION", "다른 강연 원문입니다.");
        link(otherTopicGroupId, insertMessage(otherRoomId, otherParticipant, "다른 강연 메시지", start.plusSeconds(10)));
    }

    @Test
    void 소유자는_종료된_강연방의_분석_상태와_주제_그룹을_조회한다() throws Exception {
        mvc.perform(get("/api/v1/rooms/{roomId}/ai/analyses", roomId)
                        .with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").value(roomId))
                .andExpect(jsonPath("$.data.analyses.length()").value(5))
                .andExpect(jsonPath("$.data.analyses[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.analyses[1].status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.analyses[2].status").value("PENDING"))
                .andExpect(jsonPath("$.data.analyses[3].status").value("FAILED"))
                .andExpect(jsonPath("$.data.analyses[3].errorMessage").doesNotExist())
                .andExpect(jsonPath("$.data.analyses[4].topicGroups[0].topicGroupId").value(topicGroupId))
                .andExpect(jsonPath("$.data.analyses[4].topicGroups[0].messageCount").value(2))
                .andExpect(jsonPath("$.data.analyses[4].topicGroups[0].sourceMessageIds[0]").isNumber())
                .andExpect(jsonPath("$.data.lastCompletedAt").value("2026-11-17T01:02:40Z"));
    }

    @Test
    void limit은_분석_행_기준이고_범위를_검증한다() throws Exception {
        mvc.perform(get("/api/v1/rooms/{roomId}/ai/analyses", roomId).param("limit", "2")
                        .with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analyses.length()").value(2));
        mvc.perform(get("/api/v1/rooms/{roomId}/ai/analyses", roomId).param("limit", "50")
                        .with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analyses.length()").value(5));
        mvc.perform(get("/api/v1/rooms/{roomId}/ai/analyses", roomId).param("limit", "0")
                        .with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(get("/api/v1/rooms/{roomId}/ai/analyses", roomId).param("limit", "51")
                        .with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void 분석_결과가_없는_소유_강연방은_빈_목록을_반환한다() throws Exception {
        mvc.perform(get("/api/v1/rooms/{roomId}/ai/analyses", emptyRoomId)
                        .with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analyses.length()").value(0))
                .andExpect(jsonPath("$.data.lastCompletedAt").doesNotExist());
    }

    @Test
    void 소유자는_주제_그룹에_연결된_실제_청중_원문을_조회한다() throws Exception {
        mvc.perform(get("/api/v1/rooms/{roomId}/ai/topic-groups/{topicGroupId}/messages", roomId, topicGroupId)
                        .with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topicGroupId").value(topicGroupId))
                .andExpect(jsonPath("$.data.topic").value("Redis 사용 이유"))
                .andExpect(jsonPath("$.data.messages.length()").value(2))
                .andExpect(jsonPath("$.data.messages[0].nickname").value("청중A"))
                .andExpect(jsonPath("$.data.messages[0].content").value("Redis를 꼭 사용해야 하나요?"))
                .andExpect(jsonPath("$.data.messages[0].receivedAt").value("2026-11-17T01:00:10Z"));
    }

    @Test
    void 원문이_연결되지_않은_주제_그룹은_빈_배열을_반환한다() throws Exception {
        mvc.perform(get("/api/v1/rooms/{roomId}/ai/topic-groups/{topicGroupId}/messages", roomId, emptyTopicGroupId)
                        .with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topicGroupId").value(emptyTopicGroupId))
                .andExpect(jsonPath("$.data.messages.length()").value(0));
    }

    @Test
    void 인증과_소유권을_검증하고_다른_강연방_주제_그룹은_찾지_않는다() throws Exception {
        String analyses = "/api/v1/rooms/" + roomId + "/ai/analyses";
        mvc.perform(get(analyses))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get(analyses).with(user(ownerEmail).roles("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get(analyses).with(user(otherEmail).roles("HOST")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/rooms/{roomId}/ai/topic-groups/{topicGroupId}/messages", roomId, topicGroupId)
                        .with(user(otherEmail).roles("HOST")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/rooms/{roomId}/ai/topic-groups/{topicGroupId}/messages", roomId, otherTopicGroupId)
                        .with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 존재하지_않는_강연방과_주제_그룹은_찾을_수_없다() throws Exception {
        mvc.perform(get("/api/v1/rooms/{roomId}/ai/analyses", Long.MAX_VALUE)
                        .with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/v1/rooms/{roomId}/ai/topic-groups/{topicGroupId}/messages", roomId, Long.MAX_VALUE)
                        .with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 로그인_세션의_ROLE_HOST로_조회할_수_있다() throws Exception {
        jdbc.sql("UPDATE users SET password_hash = ? WHERE email = ?")
                .params(passwordEncoder.encode(PASSWORD), ownerEmail).update();
        var login = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
                        .content(json.writeValueAsBytes(Map.of("email", ownerEmail, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        var session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();

        mvc.perform(get("/api/v1/rooms/{roomId}/ai/analyses", roomId).session(session))
                .andExpect(status().isOk());
    }

    private long insertUser(String email, String passwordHash) {
        jdbc.sql("INSERT INTO users(email, password_hash, name, created_at) VALUES (?, ?, 'host', NOW())")
                .params(email, passwordHash).update();
        return jdbc.sql("SELECT id FROM users WHERE email = ?").param(email).query(Long.class).single();
    }

    private long insertRoom(long hostId, String joinCode, String status) {
        jdbc.sql("""
                INSERT INTO lecture_rooms(host_user_id, title, join_code, status, created_at, started_at, ended_at)
                VALUES (?, '강연', ?, ?, NOW(), '2026-11-17 10:00:00', '2026-11-17 11:00:00')
                """).params(hostId, joinCode, status).update();
        return jdbc.sql("SELECT id FROM lecture_rooms WHERE join_code = ?").param(joinCode).query(Long.class).single();
    }

    private long insertParticipant(long roomId, String token, String nickname) {
        jdbc.sql("""
                INSERT INTO lecture_participants(lecture_room_id, nickname, participant_token_hash, joined_at)
                VALUES (?, ?, ?, NOW())
                """).params(roomId, nickname, token).update();
        return jdbc.sql("SELECT id FROM lecture_participants WHERE participant_token_hash = ?")
                .param(token).query(Long.class).single();
    }

    private long insertAnalysis(long roomId, LocalDateTime start, String status, LocalDateTime completedAt, String error) {
        jdbc.sql("""
                INSERT INTO ai_analyses(lecture_room_id, window_start_at, window_end_at, status, model_name,
                                        error_message, created_at, completed_at)
                VALUES (?, ?, ?, ?, 'gemini-test', ?, ?, ?)
                """).params(roomId, start, start.plusSeconds(30), status, error, start, completedAt).update();
        return jdbc.sql("""
                SELECT id FROM ai_analyses
                WHERE lecture_room_id = ? AND window_start_at = ? AND window_end_at = ?
                """).params(roomId, start, start.plusSeconds(30)).query(Long.class).single();
    }

    private long insertTopicGroup(long analysisId, String topic, String category, String summary) {
        jdbc.sql("""
                INSERT INTO ai_topic_groups(analysis_id, topic, category, summary, created_at)
                VALUES (?, ?, ?, ?, NOW())
                """).params(analysisId, topic, category, summary).update();
        return jdbc.sql("SELECT id FROM ai_topic_groups WHERE analysis_id = ? AND topic = ?")
                .params(analysisId, topic).query(Long.class).single();
    }

    private long insertMessage(long roomId, long participantId, String content, LocalDateTime receivedAt) {
        jdbc.sql("""
                INSERT INTO chat_messages(lecture_room_id, participant_id, content, received_at)
                VALUES (?, ?, ?, ?)
                """).params(roomId, participantId, content, receivedAt).update();
        return jdbc.sql("""
                SELECT id FROM chat_messages
                WHERE lecture_room_id = ? AND participant_id = ? AND content = ? AND received_at = ?
                """).params(roomId, participantId, content, receivedAt).query(Long.class).single();
    }

    private void link(long topicGroupId, long messageId) {
        jdbc.sql("INSERT INTO ai_topic_group_messages(topic_group_id, chat_message_id) VALUES (?, ?)")
                .params(topicGroupId, messageId).update();
    }
}
