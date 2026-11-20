package com.react.room;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.auth.entity.User;
import com.react.auth.repository.UserRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LectureRoomLifecycleIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired UserRepository userRepository;

    private String ownerEmail;
    private String otherEmail;

    @BeforeEach
    void prepareDatabase() {
        assertThat(jdbc.sql("SELECT DATABASE()")
                .query(String.class)
                .single()).endsWith("_test");

        Flyway.configure()
                .dataSource(dataSource)
                .load()
                .migrate();

        String key = UUID.randomUUID().toString();

        ownerEmail = key + "@example.com";
        otherEmail = "other-" + key + "@example.com";

        userRepository.saveAndFlush(
                new User(ownerEmail, "hash", "강연자A")
        );

        userRepository.saveAndFlush(
                new User(otherEmail, "hash", "강연자B")
        );
    }

    @Test
    void READY_강연방을_시작하면_LIVE가_되고_시작시각이_저장된다()
            throws Exception {
        long roomId = createRoom();

        mvc.perform(post("/api/v1/rooms/{roomId}/start", roomId)
                        .with(user(ownerEmail).roles("HOST"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").value(roomId))
                .andExpect(jsonPath("$.data.status").value("LIVE"))
                .andExpect(jsonPath("$.data.startedAt").isNotEmpty());

        assertThat(roomStatus(roomId)).isEqualTo("LIVE");

        assertThat(jdbc.sql("""
                SELECT started_at FROM lecture_rooms WHERE id = ?
                """).param(roomId)
                .query(LocalDateTime.class)
                .single()).isNotNull();
    }

    @Test
    void LIVE_강연방을_종료하면_ENDED가_되고_종료시각이_저장된다()
            throws Exception {
        long roomId = createRoom();
        startRoom(roomId);

        mvc.perform(post("/api/v1/rooms/{roomId}/end", roomId)
                        .with(user(ownerEmail).roles("HOST"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").value(roomId))
                .andExpect(jsonPath("$.data.status").value("ENDED"))
                .andExpect(jsonPath("$.data.endedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.reportReady").value(false))
                .andExpect(jsonPath("$.data.pendingFinalization").value(true));

        assertThat(roomStatus(roomId)).isEqualTo("ENDED");

        assertThat(jdbc.sql("""
                SELECT ended_at FROM lecture_rooms WHERE id = ?
                """).param(roomId)
                .query(LocalDateTime.class)
                .single()).isNotNull();
    }

    @Test
    void READY_상태에서_종료하면_409를_반환한다()
            throws Exception {
        long roomId = createRoom();

        mvc.perform(post("/api/v1/rooms/{roomId}/end", roomId)
                        .with(user(ownerEmail).roles("HOST"))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ROOM_STATUS"));

        assertThat(roomStatus(roomId)).isEqualTo("READY");
    }

    @Test
    void LIVE_상태에서_다시_시작하면_409를_반환한다()
            throws Exception {
        long roomId = createRoom();
        startRoom(roomId);

        mvc.perform(post("/api/v1/rooms/{roomId}/start", roomId)
                        .with(user(ownerEmail).roles("HOST"))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ROOM_STATUS"));
    }

    @Test
    void 종료된_강연방은_재시작하거나_다시_종료할_수_없다()
            throws Exception {
        long roomId = createRoom();
        startRoom(roomId);
        endRoom(roomId);

        mvc.perform(post("/api/v1/rooms/{roomId}/start", roomId)
                        .with(user(ownerEmail).roles("HOST"))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ROOM_STATUS"));

        mvc.perform(post("/api/v1/rooms/{roomId}/end", roomId)
                        .with(user(ownerEmail).roles("HOST"))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ROOM_STATUS"));

        assertThat(roomStatus(roomId)).isEqualTo("ENDED");
    }

    @Test
    void 다른_강연자는_시작과_종료를_할_수_없다()
            throws Exception {
        long roomId = createRoom();

        mvc.perform(post("/api/v1/rooms/{roomId}/start", roomId)
                        .with(user(otherEmail).roles("HOST"))
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        startRoom(roomId);

        mvc.perform(post("/api/v1/rooms/{roomId}/end", roomId)
                        .with(user(otherEmail).roles("HOST"))
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 로그인하지_않은_사용자는_시작할_수_없다()
            throws Exception {
        long roomId = createRoom();

        mvc.perform(post("/api/v1/rooms/{roomId}/start", roomId)
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void CSRF_토큰_없이_강연을_시작할_수_없다()
            throws Exception {
        long roomId = createRoom();

        mvc.perform(post("/api/v1/rooms/{roomId}/start", roomId)
                        .with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isForbidden());
    }


    @Test
    void 강연_종료시_진행중인_투표도_마감한다()
            throws Exception {
        long roomId = createRoom();
        startRoom(roomId);

        // 실행 환경에 관계없이 한국 시간 기준으로 테스트 데이터 생성
        LocalDateTime nowSeoul =
                LocalDateTime.now(ZoneId.of("Asia/Seoul"));

        jdbc.sql("""
            INSERT INTO polls (
                lecture_room_id,
                question,
                status,
                created_at,
                closes_at
            ) VALUES (?, '진행 중인 투표', 'OPEN', ?, ?)
            """)
                .params(
                        roomId,
                        nowSeoul.minusSeconds(10),
                        nowSeoul.plusMinutes(2)
                )
                .update();

        endRoom(roomId);

        assertThat(jdbc.sql("""
            SELECT status FROM polls
            WHERE lecture_room_id = ?
            """).param(roomId)
                .query(String.class)
                .single()).isEqualTo("CLOSED");

        assertThat(jdbc.sql("""
            SELECT close_reason FROM polls
            WHERE lecture_room_id = ?
            """).param(roomId)
                .query(String.class)
                .single()).isEqualTo("ROOM_ENDED");
    }


    @Test
    void 이미_만료된_투표는_TIMEOUT으로_마감한다()
            throws Exception {
        long roomId = createRoom();
        startRoom(roomId);

        jdbc.sql("""
                INSERT INTO polls (
                    lecture_room_id,
                    question,
                    status,
                    created_at,
                    closes_at
                ) VALUES (?, '만료된 투표', 'OPEN', ?, ?)
                """)
                .params(
                        roomId,
                        LocalDateTime.now().minusMinutes(2),
                        LocalDateTime.now().minusMinutes(1)
                )
                .update();

        endRoom(roomId);

        assertThat(jdbc.sql("""
                SELECT close_reason FROM polls
                WHERE lecture_room_id = ?
                """).param(roomId)
                .query(String.class)
                .single()).isEqualTo("TIMEOUT");
    }

    private long createRoom() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/rooms")
                        .with(user(ownerEmail).roles("HOST"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "SCRUM-130 강연",
                                  "description": "시작 종료 테스트"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode data = json.readTree(
                result.getResponse().getContentAsString()
        ).path("data");

        return data.path("roomId").asLong();
    }

    private void startRoom(long roomId) throws Exception {
        mvc.perform(post("/api/v1/rooms/{roomId}/start", roomId)
                        .with(user(ownerEmail).roles("HOST"))
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    private void endRoom(long roomId) throws Exception {
        mvc.perform(post("/api/v1/rooms/{roomId}/end", roomId)
                        .with(user(ownerEmail).roles("HOST"))
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    private String roomStatus(long roomId) {
        return jdbc.sql("""
                SELECT status FROM lecture_rooms WHERE id = ?
                """).param(roomId)
                .query(String.class)
                .single();
    }
}
