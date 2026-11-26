package com.react.ai;

import com.react.ai.service.AiQuestionGroupService;
import com.react.ai.repository.AiQuestionGroupRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiQuestionGroupIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired AiQuestionGroupService service;
    @Autowired AiQuestionGroupRepository questions;
    String email;
    long room, otherRoom, group, opinion, otherGroup, analysis, pendingGroup;

    @BeforeEach
    void setUp() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).isEqualTo("react_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        String key = UUID.randomUUID().toString();
        email = key + "@example.com";
        long host = user(email), other = user("other-" + email);
        room = room(host, key, "ENDED");
        otherRoom = room(other, "other-" + key, "LIVE");
        analysis = analysis(room);
        group = group(analysis, "QUESTION", "첫 질문");
        opinion = group(analysis, "OPINION", "의견");
        pendingGroup = group(analysis(room, "PENDING"), "QUESTION", "진행 중 질문");
        otherGroup = group(analysis(otherRoom), "QUESTION", "다른 방 질문");
    }

    @Test
    void 완료_취소_중복_목록_및_기존_조회를_검증한다() throws Exception {
        String path = path(room, group);
        mvc.perform(get("/api/v1/rooms/{room}/ai/question-groups", room).with(host()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.questionGroups[0].topicGroupId").value(group));
        for (int i = 0; i < 2; i++) mvc.perform(put(path).with(host()).with(csrf())
                .contentType("application/json").content("{\"answered\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.answerStatus").value("ANSWERED"));
        assertThat(jdbc.sql("SELECT answered_by_user_id FROM ai_topic_groups WHERE id=?").param(group)
                .query(Long.class).single()).isPositive();
        mvc.perform(get("/api/v1/rooms/{room}/ai/question-groups", room).param("answerStatus", "ANSWERED").with(host()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.questionGroups[0].topicGroupId").value(group));
        mvc.perform(get("/api/v1/rooms/{room}/ai/analyses", room).with(host()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/rooms/{room}/ai/topic-groups/{group}/messages", room, group).with(host()))
                .andExpect(status().isOk());
        for (int i = 0; i < 2; i++) mvc.perform(put(path).with(host()).with(csrf())
                .contentType("application/json").content("{\"answered\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.answerStatus").value("UNANSWERED"));
        assertThat(jdbc.sql("SELECT answered_at IS NULL FROM ai_topic_groups WHERE id=?").param(group)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void 권한과_질문_유형_및_존재를_검증한다() throws Exception {
        mvc.perform(put(path(room, group)).with(csrf()).contentType("application/json")
                .content("{\"answered\":true}")).andExpect(status().isUnauthorized());
        mvc.perform(put(path(room, group)).with(SecurityMockMvcRequestPostProcessors.user(email).roles("USER"))
                .with(csrf()).contentType("application/json").content("{\"answered\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(put(path(otherRoom, otherGroup)).with(host()).with(csrf()).contentType("application/json")
                .content("{\"answered\":true}")).andExpect(status().isForbidden());
        mvc.perform(put(path(room, opinion)).with(host()).with(csrf()).contentType("application/json")
                .content("{\"answered\":true}")).andExpect(status().isConflict());
        mvc.perform(put(path(room, pendingGroup)).with(host()).with(csrf()).contentType("application/json")
                .content("{\"answered\":true}")).andExpect(status().isConflict());
        mvc.perform(put(path(room, -1)).with(host()).with(csrf()).contentType("application/json")
                .content("{\"answered\":true}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/rooms/{room}/ai/question-groups", room).param("limit", "51").with(host()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 동시_완료_요청은_단일_상태로_수렴한다() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = pool.submit(() -> { start.await(); return service.change(room, group, email, true); });
            var second = pool.submit(() -> { start.await(); return service.change(room, group, email, true); });
            start.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).answerStatus()).isEqualTo("ANSWERED");
            assertThat(second.get(5, TimeUnit.SECONDS).answerStatus()).isEqualTo("ANSWERED");
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_topic_groups WHERE id=? AND answered_at IS NOT NULL")
                .param(group).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void 중복_상태_변경은_조건부_UPDATE_0건이어도_현재_상태를_반환한다() {
        assertThat(questions.changeAnswerStatus(room, group, hostId(), true)).isEqualTo(1);
        assertThat(questions.changeAnswerStatus(room, group, hostId(), true)).isZero();
        assertThat(service.change(room, group, email, true).answerStatus()).isEqualTo("ANSWERED");
        assertThat(questions.changeAnswerStatus(room, group, hostId(), false)).isEqualTo(1);
        assertThat(questions.changeAnswerStatus(room, group, hostId(), false)).isZero();
        assertThat(service.change(room, group, email, false).answerStatus()).isEqualTo("UNANSWERED");
    }

    @Test
    void 질문_목록은_오래된_순서로_커서를_이어가고_상태별로_나뉜다() throws Exception {
        long second = group(analysis, "QUESTION", "두 번째 질문");
        mvc.perform(get("/api/v1/rooms/{room}/ai/question-groups", room).param("limit", "1").with(host()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questionGroups[0].topicGroupId").value(group))
                .andExpect(jsonPath("$.data.nextCursor").value(group));
        mvc.perform(get("/api/v1/rooms/{room}/ai/question-groups", room)
                .param("limit", "1").param("cursor", Long.toString(group)).with(host()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questionGroups[0].topicGroupId").value(second))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
        mvc.perform(get("/api/v1/rooms/{room}/ai/question-groups", room)
                .param("cursor", Long.toString(otherGroup)).with(host()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/rooms/{room}/ai/question-groups", room)
                .param("cursor", Long.toString(opinion)).with(host()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 동시_완료와_취소는_응답과_DB_상태가_유효한_상태로_수렴한다() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var finish = pool.submit(() -> { start.await(); return service.change(room, group, email, true); });
            var cancel = pool.submit(() -> { start.await(); return service.change(room, group, email, false); });
            start.countDown();
            finish.get(5, TimeUnit.SECONDS);
            cancel.get(5, TimeUnit.SECONDS);
        }
        var state = jdbc.sql("SELECT answered_at IS NULL AS unanswered, answered_by_user_id IS NULL AS no_host " +
                "FROM ai_topic_groups WHERE id=?").param(group).query((rs, row) ->
                new boolean[] {rs.getBoolean("unanswered"), rs.getBoolean("no_host")}).single();
        assertThat(state[0]).isEqualTo(state[1]);
    }

    @Test
    void 동일_완료_취소_요청과_51개_이상_질문_페이지를_검증한다() throws Exception {
        for (int i = 0; i < 51; i++) group(analysis, "QUESTION", "누적 질문" + i);
        assertThat(service.change(room, group, email, true).answerStatus()).isEqualTo("ANSWERED");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var one = pool.submit(() -> { start.await(); return service.change(room, group, email, false); });
            var two = pool.submit(() -> { start.await(); return service.change(room, group, email, false); });
            start.countDown();
            assertThat(one.get(5, TimeUnit.SECONDS).answerStatus()).isEqualTo("UNANSWERED");
            assertThat(two.get(5, TimeUnit.SECONDS).answerStatus()).isEqualTo("UNANSWERED");
        }
        var first = service.list(room, email, "UNANSWERED", 50, null);
        assertThat(first.questionGroups()).hasSize(50);
        assertThat(first.nextCursor()).isNotNull();
        var second = service.list(room, email, "UNANSWERED", 50, first.nextCursor());
        assertThat(second.questionGroups()).isNotEmpty();
        assertThat(second.nextCursor()).isNull();
    }

    private SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor host() {
        return SecurityMockMvcRequestPostProcessors.user(email).roles("HOST");
    }
    private String path(long roomId, long id) {
        return "/api/v1/rooms/" + roomId + "/ai/topic-groups/" + id + "/answer-status";
    }
    private long user(String mail) {
        jdbc.sql("INSERT INTO users(email,password_hash,name,created_at) VALUES (?, 'hash', 'host', NOW())")
                .param(mail).update();
        return jdbc.sql("SELECT id FROM users WHERE email=?").param(mail).query(Long.class).single();
    }
    private long room(long host, String key, String status) {
        jdbc.sql("""
                INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at,started_at,ended_at)
                VALUES (?,'강연',?,?,NOW(),'2026-11-17 10:00:00','2026-11-17 11:00:00')
                """).params(host, key, status).update();
        return jdbc.sql("SELECT id FROM lecture_rooms WHERE join_code=?").param(key).query(Long.class).single();
    }
    private long analysis(long roomId) {
        return analysis(roomId, "COMPLETED");
    }
    private long analysis(long roomId, String status) {
        String start = "PENDING".equals(status) ? "2026-11-17 10:01:00" : "2026-11-17 10:00:00";
        String end = "PENDING".equals(status) ? "2026-11-17 10:01:30" : "2026-11-17 10:00:30";
        jdbc.sql("""
                INSERT INTO ai_analyses(lecture_room_id,window_start_at,window_end_at,status,created_at,completed_at)
                VALUES (?,?,?, ?,NOW(),NOW())
                """).params(roomId, start, end, status).update();
        return jdbc.sql("SELECT id FROM ai_analyses WHERE lecture_room_id=? AND status=? ORDER BY id DESC")
                .params(roomId, status).query(Long.class).single();
    }
    private long group(long analysisId, String category, String title) {
        jdbc.sql("INSERT INTO ai_topic_groups(analysis_id,topic,category,summary,created_at) VALUES (?,?,?,'요약',NOW())")
                .params(analysisId,title,category).update();
        return jdbc.sql("SELECT id FROM ai_topic_groups WHERE analysis_id=? AND topic=?")
                .params(analysisId,title).query(Long.class).single();
    }
    private long hostId() {
        return jdbc.sql("SELECT id FROM users WHERE email=?").param(email).query(Long.class).single();
    }
}
