package com.react.room;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.auth.entity.User;
import com.react.auth.repository.UserRepository;
import com.react.room.entity.LectureRoom;
import com.react.room.repository.LectureRoomRepository;
import jakarta.servlet.http.Cookie;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LectureParticipantIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired UserRepository users;
    @Autowired LectureRoomRepository rooms;

    private User host;
    private RoomRef roomA;
    private RoomRef roomB;

    private record RoomRef(Long id, String code) {
    }

    @BeforeEach
    void prepareDatabase() {
        assertThat(jdbc.sql("SELECT DATABASE()")
                .query(String.class)
                .single()).endsWith("_test");

        Flyway.configure().dataSource(dataSource).load().migrate();

        host = users.saveAndFlush(new User(
                UUID.randomUUID() + "@example.com",
                "hash",
                "강연자"
        ));

        roomA = createRoom();
        roomB = createRoom();
    }

    @Test
    void 최초_입장은_201_과_해당방_전용쿠키를_발급한다()
            throws Exception {
        MvcResult first = join(roomA, "청중A");

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(data(first).path("participantId").asLong()).isPositive();
        assertThat(data(first).path("roomId").asLong()).isEqualTo(roomA.id());
        assertThat(data(first).path("nickname").asText()).isEqualTo("청중A");
        assertThat(data(first).path("roomStatus").asText()).isEqualTo("READY");

        String setCookie = first.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains(
                cookieName(roomA),
                "HttpOnly",
                "SameSite=Lax"
        );

        String token = cookieFrom(first, roomA).getValue();

        String hash = jdbc.sql("""
                SELECT participant_token_hash
                FROM lecture_participants
                WHERE id = ?
                """)
                .param(data(first).path("participantId").asLong())
                .query(String.class)
                .single();

        assertThat(hash).hasSize(64).isNotEqualTo(token);
    }

    @Test
    void 같은_방_재입장은_기존_ID를_복원하고_추가_저장하지_않는다()
            throws Exception {
        MvcResult first = join(roomA, "청중A");
        Cookie cookieA = cookieFrom(first, roomA);

        MvcResult second = join(roomA, "새 닉네임", cookieA);

        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(second).path("participantId").asLong())
                .isEqualTo(data(first).path("participantId").asLong());
        assertThat(data(second).path("nickname").asText()).isEqualTo("청중A");
        assertThat(second.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .isNull();
        assertThat(participantCount(roomA)).isEqualTo(1L);
    }

    @Test
    void A방_B방_A방_이동에도_기존_참여자_ID를_유지한다()
            throws Exception {
        MvcResult firstA = join(roomA, "청중A");
        Cookie cookieA = cookieFrom(firstA, roomA);

        MvcResult firstB = join(roomB, "청중B", cookieA);
        Cookie cookieB = cookieFrom(firstB, roomB);

        assertThat(cookieA.getName()).isNotEqualTo(cookieB.getName());

        // 브라우저에는 두 강연방의 쿠키가 모두 저장된 상태다.
        MvcResult returnedA = join(
                roomA,
                "청중A",
                cookieA,
                cookieB
        );

        assertThat(returnedA.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(returnedA).path("participantId").asLong())
                .isEqualTo(data(firstA).path("participantId").asLong());
        assertThat(participantCount(roomA)).isEqualTo(1L);
        assertThat(participantCount(roomB)).isEqualTo(1L);
    }

    @Test
    void 같은_브라우저의_두_방_동시_접속이_가능하다()
            throws Exception {
        Cookie cookieA = cookieFrom(join(roomA, "A"), roomA);
        Cookie cookieB = cookieFrom(join(roomB, "B", cookieA), roomB);

        mvc.perform(
                        get("/api/v1/rooms/{roomId}/participants/me", roomA.id())
                                .cookie(cookieA, cookieB)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").value(roomA.id()));

        mvc.perform(
                        get("/api/v1/rooms/{roomId}/participants/me", roomB.id())
                                .cookie(cookieA, cookieB)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").value(roomB.id()));
    }

    @Test
    void 유효한_쿠키가_없으면_내_참여정보를_조회할_수_없다()
            throws Exception {
        mvc.perform(
                        get("/api/v1/rooms/{roomId}/participants/me", roomA.id())
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 다른_방의_쿠키만_보내면_인증되지_않는다()
            throws Exception {
        Cookie cookieA = cookieFrom(join(roomA, "A"), roomA);

        mvc.perform(
                        get("/api/v1/rooms/{roomId}/participants/me", roomB.id())
                                .cookie(cookieA)
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 다른_방의_토큰을_이름만_바꿔_위조해도_접근할_수_없다()
            throws Exception {
        Cookie cookieA = cookieFrom(join(roomA, "A"), roomA);
        Cookie forged = new Cookie(
                cookieName(roomB),
                cookieA.getValue()
        );

        mvc.perform(
                        get("/api/v1/rooms/{roomId}/participants/me", roomB.id())
                                .cookie(forged)
                )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 쿠키_토큰이_변조되면_인증되지_않는다()
            throws Exception {
        Cookie invalid = new Cookie(
                cookieName(roomA),
                "a".repeat(43)
        );

        mvc.perform(
                        get("/api/v1/rooms/{roomId}/participants/me", roomA.id())
                                .cookie(invalid)
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 만료된_세션으로_재입장하면_새_참여자_정보를_발급한다()
            throws Exception {
        MvcResult first = join(roomA, "청중A");
        Long id = data(first).path("participantId").asLong();
        Cookie oldCookie = cookieFrom(first, roomA);

        // 기본 TTL 24시간보다 과거인 48시간 전으로 DB 가입 시각을 변경한다.
        jdbc.sql("""
                UPDATE lecture_participants
                SET joined_at = DATE_SUB(NOW(), INTERVAL 2 DAY)
                WHERE id = ?
                """)
                .param(id)
                .update();

        MvcResult again = join(roomA, "청중A", oldCookie);

        assertThat(again.getResponse().getStatus()).isEqualTo(201);
        assertThat(data(again).path("participantId").asLong()).isNotEqualTo(id);
        assertThat(cookieFrom(again, roomA).getValue())
                .isNotEqualTo(oldCookie.getValue());
    }

    @Test
    void 종료된_방은_신규_입장을_거절한다()
            throws Exception {
        endRoom(roomA);

        mvc.perform(
                        post("/api/v1/public/rooms/{joinCode}/participants", roomA.code())
                                .with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"nickname\":\"청중A\"}")
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROOM_ENDED"));
    }

    @Test
    void 종료된_방에서도_기존_참여자는_정보를_조회할_수_있다()
            throws Exception {
        Cookie cookieA = cookieFrom(join(roomA, "청중A"), roomA);
        endRoom(roomA);

        mvc.perform(
                        get("/api/v1/rooms/{roomId}/participants/me", roomA.id())
                                .cookie(cookieA)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("청중A"));
    }

    @Test
    void 존재하지_않는_참여코드는_404를_반환한다()
            throws Exception {
        mvc.perform(
                        post("/api/v1/public/rooms/{joinCode}/participants", "INVALIDCODE")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"nickname\":\"청중A\"}")
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVALID_JOIN_CODE"));
    }

    @Test
    void 공백_닉네임은_400을_반환한다()
            throws Exception {
        mvc.perform(
                        post("/api/v1/public/rooms/{joinCode}/participants", roomA.code())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"nickname\":\"   \"}")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NICKNAME"));
    }

    @Test
    void CSRF_토큰이_없는_입장_POST는_거부한다()
            throws Exception {
        mvc.perform(
                        post("/api/v1/public/rooms/{joinCode}/participants", roomA.code())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"nickname\":\"청중A\"}")
                )
                .andExpect(status().isForbidden());
    }

    @Test
    void 동일한_입장키로_재전송하면_참여자_ID와_쿠키가_같다()
            throws Exception {
        String key = UUID.randomUUID().toString();

        MvcResult first = joinWithKey(roomA, "청중A", key);
        MvcResult second = joinWithKey(roomA, "청중A", key);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);

        assertThat(data(first).path("participantId").asLong())
                .isEqualTo(data(second).path("participantId").asLong());
        assertThat(cookieFrom(first, roomA).getValue())
                .isEqualTo(cookieFrom(second, roomA).getValue());
        assertThat(participantCount(roomA)).isEqualTo(1L);
    }

    @Test
    void 동일한_입장키_동시_요청은_한_명만_생성한다()
            throws Exception {
        String key = UUID.randomUUID().toString();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> first = executor.submit(() -> {
                start.await();
                return joinWithKey(roomA, "청중A", key);
            });

            Future<MvcResult> second = executor.submit(() -> {
                start.await();
                return joinWithKey(roomA, "청중A", key);
            });

            start.countDown();

            MvcResult firstResult = first.get();
            MvcResult secondResult = second.get();

            assertThat(firstResult.getResponse().getStatus()
                    + secondResult.getResponse().getStatus())
                    .isEqualTo(201 + 200);

            assertThat(data(firstResult).path("participantId").asLong())
                    .isEqualTo(data(secondResult).path("participantId").asLong());

            assertThat(cookieFrom(firstResult, roomA).getValue())
                    .isEqualTo(cookieFrom(secondResult, roomA).getValue());

            assertThat(participantCount(roomA)).isEqualTo(1L);
        }
    }

    @Test
    void 신규입장에_키가_없으면_400이다()
            throws Exception {
        mvc.perform(
                        post("/api/v1/public/rooms/{joinCode}/participants", roomA.code())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"nickname\":\"청중A\"}")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void 종료된_방에_기존_입장키로_재시도하면_기존_참여자를_복원한다()
            throws Exception {
        String key = UUID.randomUUID().toString();

        MvcResult first = joinWithKey(roomA, "청중A", key);
        endRoom(roomA);

        MvcResult again = joinWithKey(roomA, "청중A", key);

        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(again).path("participantId").asLong())
                .isEqualTo(data(first).path("participantId").asLong());
        assertThat(participantCount(roomA)).isEqualTo(1L);
    }

    @Test
    void 만료된_입장키를_재사용하면_409를_반환한다()
            throws Exception {
        String key = UUID.randomUUID().toString();

        MvcResult first = joinWithKey(roomA, "청중A", key);
        Long id = data(first).path("participantId").asLong();

        jdbc.sql("""
                UPDATE lecture_participants
                SET joined_at = DATE_SUB(NOW(), INTERVAL 2 DAY)
                WHERE id = ?
                """)
                .param(id)
                .update();

        mvc.perform(
                        post("/api/v1/public/rooms/{joinCode}/participants", roomA.code())
                                .with(csrf())
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"nickname\":\"청중A\"}")
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    private RoomRef createRoom() {
        String code = UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 12)
                .toUpperCase();

        LectureRoom room = rooms.saveAndFlush(
                new LectureRoom(
                        host,
                        "청중 입장 테스트",
                        "테스트 강연",
                        code
                )
        );

        return new RoomRef(room.getId(), code);
    }

    private MvcResult join(
            RoomRef room,
            String nickname,
            Cookie... cookies
    ) throws Exception {
        return joinWithKey(
                room,
                nickname,
                UUID.randomUUID().toString(),
                cookies
        );
    }

    private MvcResult joinWithKey(
            RoomRef room,
            String nickname,
            String key,
            Cookie... cookies
    ) throws Exception {
        MockHttpServletRequestBuilder request = post(
                "/api/v1/public/rooms/{joinCode}/participants",
                room.code()
        )
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                        java.util.Map.of("nickname", nickname)
                ));

        if (cookies.length > 0) {
            request.cookie(cookies);
        }

        return mvc.perform(request).andReturn();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(
                result.getResponse().getContentAsString()
        ).path("data");
    }

    private Cookie cookieFrom(MvcResult result, RoomRef room) {
        String header = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header).isNotNull();

        String prefix = cookieName(room) + "=";
        String firstPart = header.split(";", 2)[0];
        assertThat(firstPart).startsWith(prefix);

        return new Cookie(
                cookieName(room),
                firstPart.substring(prefix.length())
        );
    }

    private String cookieName(RoomRef room) {
        return "PARTICIPANT_SESSION_" + room.id();
    }

    private long participantCount(RoomRef room) {
        return jdbc.sql("""
                SELECT COUNT(*)
                FROM lecture_participants
                WHERE lecture_room_id = ?
                """)
                .param(room.id())
                .query(Long.class)
                .single();
    }

    private void endRoom(RoomRef room) {
        jdbc.sql("""
                UPDATE lecture_rooms
                SET status = 'ENDED'
                WHERE id = ?
                """)
                .param(room.id())
                .update();
    }
}
