package com.react.room;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.auth.entity.User;
import com.react.auth.repository.UserRepository;
import com.react.room.service.LectureParticipantService;
import com.react.stt.dto.SttGap;
import com.react.stt.dto.SttSessionView;
import com.react.stt.service.SttSessionService;
import jakarta.servlet.http.Cookie;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    @Autowired LectureParticipantService participants;
    @Autowired SttSessionService stt;

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
        LocalDateTime nowSeoul = LocalDateTime.now(ZoneId.of("Asia/Seoul"));

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
    void 강연_종료시_해당_방의_녹음과_열린_누락_구간만_같은_시각에_닫는다() throws Exception {
        long roomId = createRoom();
        long otherRoomId = createRoom();
        startRoom(roomId);
        startRoom(otherRoomId);
        UUID key = UUID.randomUUID();
        UUID otherKey = UUID.randomUUID();
        var recording = startRecordingWithGap(roomId, key);
        var otherRecording = startRecordingWithGap(otherRoomId, otherKey);

        endRoom(roomId);

        var endedAt = jdbc.sql("SELECT ended_at FROM lecture_rooms WHERE id = ?").param(roomId)
                .query(LocalDateTime.class).single().atZone(ZoneId.of("Asia/Seoul")).toInstant();
        var stopped = stt.start(roomId, ownerEmail, key).session();
        assertThat(stopped.sttSessionId()).isEqualTo(recording.sttSessionId());
        assertThat(stopped.status()).isEqualTo("STOPPED");
        assertThat(stopped.endedAt()).isEqualTo(endedAt);
        assertThat(stt.records(roomId, ownerEmail, null, null).gaps()).singleElement()
                .satisfies(gap -> assertThat(gap.endedAt()).isEqualTo(endedAt));
        assertThat(stt.start(otherRoomId, ownerEmail, otherKey).session()).isEqualTo(otherRecording);
        assertThat(stt.records(otherRoomId, ownerEmail, null, null).gaps()).singleElement()
                .satisfies(gap -> assertThat(gap.endedAt()).isNull());
    }

    @Test
    void 강연_종료시_이미_종료된_녹음_세션과_누락_구간의_시각을_보존한다() throws Exception {
        long roomId = createRoom();
        startRoom(roomId);
        UUID key = UUID.randomUUID();
        var recording = startRecordingWithGap(roomId, key);
        stt.stop(roomId, recording.sttSessionId(), ownerEmail, SttSessionService.StopReason.ERROR);
        // 같은 초에 테스트를 마쳐도 기존 종료 시각을 덮어쓰는 회귀를 검출한다.
        jdbc.sql("UPDATE stt_sessions SET started_at = started_at - INTERVAL 1 MINUTE, ended_at = ended_at - INTERVAL 1 MINUTE WHERE id = ?")
                .param(recording.sttSessionId()).update();
        jdbc.sql("UPDATE stt_gaps SET started_at = started_at - INTERVAL 1 MINUTE, ended_at = ended_at - INTERVAL 1 MINUTE WHERE stt_session_id = ?")
                .param(recording.sttSessionId()).update();
        var stopped = stt.start(roomId, ownerEmail, key).session();
        var gaps = stt.records(roomId, ownerEmail, null, null).gaps();
        startRecordingWithGap(roomId, UUID.randomUUID());

        endRoom(roomId);

        assertThat(stt.start(roomId, ownerEmail, key).session()).isEqualTo(stopped);
        assertThat(stt.records(roomId, ownerEmail, null, null).gaps()).containsAll(gaps);
    }

    private SttSessionView startRecordingWithGap(long roomId, UUID key) {
        var session = stt.start(roomId, ownerEmail, key).session();
        stt.openGap(roomId, session.sttSessionId(), ownerEmail,
                new SttGap.OpenRequest(key, "gap", SttGap.Reason.ERROR, session.startedAt()));
        return session;
    }

    @Test
    void 이미_만료된_투표는_TIMEOUT으로_마감한다()
            throws Exception {
        long roomId = createRoom();
        startRoom(roomId);

        // CI와 로컬에서 동일하게 한국 시간 사용
        LocalDateTime nowSeoul = LocalDateTime.now(ZoneId.of("Asia/Seoul"));

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
                        nowSeoul.minusMinutes(2),
                        nowSeoul.minusMinutes(1)
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

    @Test
    void 준비_방을_삭제하면_해당_방의_초대와_모든_청중_세션만_사라진다() throws Exception {
        long roomId = createRoom();
        String code = joinCode(roomId);
        var joined = participants.join(code, "대기자", UUID.randomUUID().toString(), Map.of());
        participants.join(code, "두 번째 대기자", UUID.randomUUID().toString(), Map.of());
        long otherRoomId = createRoom();
        var other = participants.join(joinCode(otherRoomId), "다른 방 대기자", UUID.randomUUID().toString(), Map.of());

        mvc.perform(delete("/api/v1/rooms/{id}", roomId).with(user(ownerEmail).roles("HOST")).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/rooms/{id}", roomId).with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
        mvc.perform(get("/api/v1/public/rooms/{code}", code))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVALID_JOIN_CODE"));
        mvc.perform(get("/api/v1/rooms/{id}/participants/me", roomId)
                        .cookie(new Cookie("PARTICIPANT_SESSION_" + roomId, joined.rawToken())))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lecture_participants WHERE lecture_room_id = ?")
                .param(roomId).query(Long.class).single()).isZero();
        assertThat(participants.me(otherRoomId, other.rawToken()).roomId()).isEqualTo(otherRoomId);
        mvc.perform(delete("/api/v1/rooms/{id}", roomId).with(user(ownerEmail).roles("HOST")).with(csrf()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
    }

    @Test
    void 삭제는_소유자_HOST와_CSRF_토큰이_필요하다() throws Exception {
        long roomId = createRoom();
        mvc.perform(delete("/api/v1/rooms/{id}", roomId).with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/rooms/{id}", roomId).with(user(ownerEmail).roles("USER")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/rooms/{id}", roomId).with(user(otherEmail).roles("HOST")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/rooms/{id}", roomId).with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isForbidden());
        assertThat(roomStatus(roomId)).isEqualTo("READY");
    }

    @Test
    void 이미_시작했거나_종료한_방은_삭제할_수_없다() throws Exception {
        long roomId = createRoom();
        var joined = participants.join(joinCode(roomId), "대기자", UUID.randomUUID().toString(), Map.of());
        startRoom(roomId);
        mvc.perform(delete("/api/v1/rooms/{id}", roomId).with(user(ownerEmail).roles("HOST")).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_ROOM_STATUS"));
        endRoom(roomId);
        mvc.perform(delete("/api/v1/rooms/{id}", roomId).with(user(ownerEmail).roles("HOST")).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_ROOM_STATUS"));
        assertThat(participants.me(roomId, joined.rawToken()).roomId()).isEqualTo(roomId);
    }

    @Test
    void 준비_방에_시작과_삭제가_동시에_도착해도_하나만_성공한다() throws Exception {
        long roomId = createRoom();
        var gate = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return mvc.perform(post("/api/v1/rooms/{id}/start", roomId)
                        .with(user(ownerEmail).roles("HOST")).with(csrf())).andReturn().getResponse().getStatus();
            });
            var deletion = executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return mvc.perform(delete("/api/v1/rooms/{id}", roomId)
                        .with(user(ownerEmail).roles("HOST")).with(csrf())).andReturn().getResponse().getStatus();
            });
            if (deletion.get(10, TimeUnit.SECONDS) == 204) {
                assertThat(start.get(10, TimeUnit.SECONDS)).isEqualTo(404);
            } else {
                assertThat(deletion.get()).isEqualTo(409);
                assertThat(start.get(10, TimeUnit.SECONDS)).isEqualTo(200);
                assertThat(roomStatus(roomId)).isEqualTo("LIVE");
            }
        }
    }

    private String joinCode(long roomId) {
        return jdbc.sql("SELECT join_code FROM lecture_rooms WHERE id = ?").param(roomId).query(String.class).single();
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
