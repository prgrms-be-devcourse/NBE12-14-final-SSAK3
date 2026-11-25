package com.react.global.config;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 로그인 세션을 사용해 강연방 간 REST API 접근 권한과 데이터 격리를 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomAccessIsolationIntegrationTest {

    private static final String PASSWORD = "TestPassword123!";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired PasswordEncoder passwordEncoder;

    private String ownerEmail;
    private String otherEmail;
    private MockHttpSession ownerSession;
    private MockHttpSession otherSession;

    @BeforeEach
    void prepare() throws Exception {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();

        ownerEmail = "host-" + UUID.randomUUID() + "@example.com";
        otherEmail = "other-" + UUID.randomUUID() + "@example.com";

        createHost(ownerEmail);
        createHost(otherEmail);

        ownerSession = login(ownerEmail);
        otherSession = login(otherEmail);
    }

    @Test
    void 실제_로그인한_강연자는_다른_강연방의_API에_접근할_수_없다() throws Exception {
        long room = createRoom(ownerEmail, "LIVE");

        String base = "/api/v1/rooms/" + room;

        // 방 소유자가 실제 API를 통해 이해도 체크를 생성한다.
        var created = mvc.perform(post(base + "/polls")
                        .session(ownerSession)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {
                                    "type": "UNDERSTANDING",
                                    "durationSeconds": 300
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        long pollId = json.readTree(created.getResponse().getContentAsString())
                .path("data")
                .path("pollId")
                .asLong();

        assertThat(pollId).isPositive();

        // 동일한 API에 대해 방 소유자는 접근하고 다른 강연자는 거절된다.
        for (String path : List.of(
                base,
                base + "/invitation",
                base + "/messages",
                base + "/polls",
                base + "/polls/" + pollId + "/results",
                base + "/ai/analyses"
        )) {
            mvc.perform(get(path).session(ownerSession))
                    .andExpect(status().isOk());

            mvc.perform(get(path).session(otherSession))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        // 다른 강연자는 진행 중인 강연을 종료할 수도 없다.
        mvc.perform(post(base + "/end")
                        .session(otherSession)
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // 거절된 요청으로 인해 강연방 상태가 변경되지 않아야 한다.
        assertThat(jdbc.sql("SELECT status FROM lecture_rooms WHERE id = ?")
                .param(room)
                .query(String.class)
                .single()).isEqualTo("LIVE");
    }

    @Test
    void 실제_로그인한_강연자는_다른_강연방의_보고서를_조회할_수_없다() throws Exception {
        long room = createRoom(ownerEmail, "ENDED");

        // 외부 AI 모델을 호출하지 않고 생성 완료된 보고서를 준비한다.
        jdbc.sql("""
                INSERT INTO lecture_reports(
                    lecture_room_id, status, content,
                    created_at, updated_at, generated_at
                )
                VALUES (
                    ?, 'COMPLETED',
                    JSON_OBJECT(
                        'timeline', JSON_ARRAY(),
                        'dataLimitations', JSON_ARRAY('강연방 소유자 전용 보고서')
                    ),
                    NOW(), NOW(), NOW()
                )
                """)
                .param(room)
                .update();

        String base = "/api/v1/rooms/" + room + "/reports";

        // 방 소유자는 보고서 상태와 실제 저장된 내용을 조회할 수 있다.
        mvc.perform(get(base + "/status").session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        mvc.perform(get(base).session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").value(room))
                .andExpect(jsonPath("$.data.content.dataLimitations[0]")
                        .value("강연방 소유자 전용 보고서"));

        // 다른 강연자에게는 보고서 상태와 내용 모두 공개하지 않는다.
        for (String path : List.of(base, base + "/status")) {
            mvc.perform(get(path).session(otherSession))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        // 다른 강연자는 보고서 생성 요청도 할 수 없다.
        long otherRoom = createRoom(ownerEmail, "ENDED");

        mvc.perform(post("/api/v1/rooms/{roomId}/reports", otherRoom)
                        .session(otherSession)
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM lecture_reports WHERE lecture_room_id = ?")
                .param(otherRoom)
                .query(Long.class)
                .single()).isZero();
    }

    private void createHost(String email) {
        jdbc.sql("""
                INSERT INTO users(email, password_hash, name, created_at)
                VALUES (?, ?, '강연자', NOW())
                """)
                .params(email, passwordEncoder.encode(PASSWORD))
                .update();
    }

    private long createRoom(String email, String status) {
        String code = UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 12)
                .toUpperCase();

        jdbc.sql("""
                INSERT INTO lecture_rooms(
                    host_user_id, title, join_code, status,
                    created_at, started_at, ended_at
                )
                SELECT id, '권한 격리 테스트', ?, ?,
                       NOW(), NOW(),
                       CASE WHEN ? = 'ENDED' THEN NOW() ELSE NULL END
                FROM users
                WHERE email = ?
                """)
                .params(code, status, status, email)
                .update();

        return jdbc.sql("SELECT id FROM lecture_rooms WHERE join_code = ?")
                .param(code)
                .query(Long.class)
                .single();
    }

    /** 실제 로그인 API를 호출하고 서버에 인증 정보가 저장된 세션을 반환한다. */
    private MockHttpSession login(String email) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType("application/json")
                        .content(json.writeValueAsBytes(
                                Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();

        var session = (MockHttpSession) result.getRequest().getSession(false);

        assertThat(session).isNotNull();

        return session;
    }
}