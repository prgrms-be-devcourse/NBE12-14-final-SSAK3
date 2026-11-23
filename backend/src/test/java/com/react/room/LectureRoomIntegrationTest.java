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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "ROOM_JOIN_BASE_URL=https://react.example")
class LectureRoomIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired UserRepository userRepository;

    private String ownerEmail;
    private String otherEmail;
    private Long ownerId;

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

        User owner = userRepository.saveAndFlush(
                new User(ownerEmail, "hash", "강연자A")
        );

        userRepository.saveAndFlush(
                new User(otherEmail, "hash", "강연자B")
        );

        ownerId = owner.getId();
    }

    @Test
    void 강연방을_생성하면_READY_상태와_참여코드를_반환한다()
            throws Exception {

        MvcResult result = createRoom(ownerEmail);

        JsonNode data = createdData(result);

        long roomId = data.path("roomId").asLong();
        String joinCode = data.path("joinCode").asText();

        assertThat(roomId).isPositive();
        assertThat(joinCode).hasSize(12);

        assertThat(data.path("title").asText())
                .isEqualTo("Redis 실습 강연");

        assertThat(data.path("description").asText())
                .isEqualTo("Redis와 MySQL의 역할");

        assertThat(data.path("status").asText())
                .isEqualTo("READY");

        assertThat(data.path("createdAt").asText())
                .isNotBlank();

        Long savedHostId = jdbc.sql("""
                SELECT host_user_id
                FROM lecture_rooms
                WHERE id = ?
                """)
                .param(roomId)
                .query(Long.class)
                .single();

        assertThat(savedHostId).isEqualTo(ownerId);
    }

    @Test
    void 생성_시작_종료_응답과_DB에_저장한_한국_시각이_일치한다() throws Exception {
        JsonNode created = createdData(createRoom(ownerEmail));
        long roomId = created.path("roomId").asLong();
        JsonNode started = createdData(mvc.perform(post("/api/v1/rooms/{roomId}/start", roomId)
                        .with(user(ownerEmail).roles("HOST")).with(csrf()))
                .andExpect(status().isOk()).andReturn());
        JsonNode ended = createdData(mvc.perform(post("/api/v1/rooms/{roomId}/end", roomId)
                        .with(user(ownerEmail).roles("HOST")).with(csrf()))
                .andExpect(status().isOk()).andReturn());

        record RoomTimes(LocalDateTime createdAt, LocalDateTime startedAt, LocalDateTime endedAt) {}
        RoomTimes stored = jdbc.sql("SELECT created_at, started_at, ended_at FROM lecture_rooms WHERE id = ?")
                .param(roomId)
                .query((rs, rowNumber) -> new RoomTimes(
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("started_at", LocalDateTime.class),
                        rs.getObject("ended_at", LocalDateTime.class)))
                .single();

        ZoneId seoul = ZoneId.of("Asia/Seoul");
        // 생성 응답에는 소수점 이하 초가 포함될 수 있고 DB의 DATETIME은 초까지만 저장한다.
        assertThat(stored.createdAt().atZone(seoul).toInstant())
                .isCloseTo(Instant.parse(created.path("createdAt").asText()), within(1, ChronoUnit.SECONDS));
        assertThat(stored.startedAt().atZone(seoul).toInstant())
                .isEqualTo(Instant.parse(started.path("startedAt").asText()));
        assertThat(stored.endedAt().atZone(seoul).toInstant())
                .isEqualTo(Instant.parse(ended.path("endedAt").asText()));
    }

    @Test
    void 강연방마다_서로_다른_참여코드를_발급한다()
            throws Exception {

        JsonNode first = createdData(createRoom(ownerEmail));
        JsonNode second = createdData(createRoom(ownerEmail));

        assertThat(first.path("joinCode").asText())
                .isNotEqualTo(second.path("joinCode").asText());
    }

    @Test
    @Transactional
    void 진행_목록은_내_LIVE_방만_시작_시각과_ID_내림차순으로_반환한다() throws Exception {
        long afternoon = liveRoomAt(ownerEmail, "2026-11-21 15:00:00");
        long morning = liveRoomAt(ownerEmail, "2026-11-21 09:00:00");
        long alsoAfternoon = liveRoomAt(ownerEmail, "2026-11-21 15:00:00");
        liveRoomAt(otherEmail, "2026-11-21 16:00:00");
        createRoom(ownerEmail);
        long ended = createdData(createRoom(ownerEmail)).path("roomId").asLong();
        jdbc.sql("UPDATE lecture_rooms SET status = 'ENDED' WHERE id = ?").param(ended).update();

        mvc.perform(get("/api/v1/rooms/live").with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].roomId").value(alsoAfternoon))
                .andExpect(jsonPath("$.data[0].title").value("Redis 실습 강연"))
                .andExpect(jsonPath("$.data[0].startedAt").value("2026-11-21T06:00:00Z"))
                .andExpect(jsonPath("$.data[1].roomId").value(afternoon))
                .andExpect(jsonPath("$.data[2].roomId").value(morning));
    }

    @Test
    void 진행_목록은_빈_배열을_반환하고_HOST_인증을_요구한다() throws Exception {
        mvc.perform(get("/api/v1/rooms/live").with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/api/v1/rooms/live"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/rooms/live").with(user(ownerEmail).roles("USER")))
                .andExpect(status().isForbidden());
    }

    private long liveRoomAt(String email, String startedAt) throws Exception {
        long roomId = createdData(createRoom(email)).path("roomId").asLong();
        jdbc.sql("UPDATE lecture_rooms SET status = 'LIVE', started_at = ? WHERE id = ?")
                .params(startedAt, roomId)
                .update();
        return roomId;
    }

    @Test
    @Transactional
    void 홈과_추가_조회는_내_준비방을_시각과_ID순으로_이어가며_방이_시작돼도_누락하지_않는다() throws Exception {
        long newest = readyRoomAt(ownerEmail, "2026-11-21 11:00:00");
        long firstTie = readyRoomAt(ownerEmail, "2026-11-21 10:00:00");
        long boundary = readyRoomAt(ownerEmail, "2026-11-21 10:00:00");
        long lastTie = readyRoomAt(ownerEmail, "2026-11-21 10:00:00");
        long oldest = readyRoomAt(ownerEmail, "2026-11-21 09:00:00");
        long live = liveRoomAt(ownerEmail, "2026-11-21 09:30:00");
        long ended = readyRoomAt(ownerEmail, "2026-11-21 12:00:00");
        jdbc.sql("UPDATE lecture_rooms SET status = 'ENDED' WHERE id = ?").param(ended).update();
        readyRoomAt(otherEmail, "2026-11-21 12:00:00");
        readyRoomAt(otherEmail, "2026-11-21 09:00:00");
        liveRoomAt(otherEmail, "2026-11-21 09:30:00");

        MvcResult home = mvc.perform(get("/api/v1/rooms/home").with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liveRooms.length()").value(1))
                .andExpect(jsonPath("$.data.liveRooms[0].roomId").value(live))
                .andExpect(jsonPath("$.data.readyRooms.rooms.length()").value(3))
                .andExpect(jsonPath("$.data.readyRooms.rooms[0].roomId").value(newest))
                .andExpect(jsonPath("$.data.readyRooms.rooms[0].title").value("Redis 실습 강연"))
                .andExpect(jsonPath("$.data.readyRooms.rooms[0].createdAt").value("2026-11-21T02:00:00Z"))
                .andExpect(jsonPath("$.data.readyRooms.rooms[1].roomId").value(lastTie))
                .andExpect(jsonPath("$.data.readyRooms.rooms[2].roomId").value(boundary))
                .andExpect(jsonPath("$.data.readyRooms.nextCursor").isString())
                .andReturn();
        String next = createdData(home).path("readyRooms").path("nextCursor").textValue();

        long inserted = readyRoomAt(ownerEmail, "2026-11-21 12:00:00");
        jdbc.sql("UPDATE lecture_rooms SET status = 'LIVE', started_at = ? WHERE id = ?")
                .params("2026-11-21 11:30:00", boundary).update();
        mvc.perform(get("/api/v1/rooms/ready").param("cursor", next).with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rooms.length()").value(2))
                .andExpect(jsonPath("$.data.rooms[0].roomId").value(firstTie))
                .andExpect(jsonPath("$.data.rooms[1].roomId").value(oldest))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()));

        mvc.perform(get("/api/v1/rooms/home").param("readyLimit", "6").with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liveRooms.length()").value(2))
                .andExpect(jsonPath("$.data.liveRooms[0].roomId").value(boundary))
                .andExpect(jsonPath("$.data.readyRooms.rooms.length()").value(5))
                .andExpect(jsonPath("$.data.readyRooms.rooms[0].roomId").value(inserted))
                .andExpect(jsonPath("$.data.readyRooms.rooms[4].roomId").value(oldest))
                .andExpect(jsonPath("$.data.readyRooms.nextCursor").value(nullValue()));
    }

    @Test
    void 홈과_준비방_조회는_빈_목록을_반환하고_권한과_페이지_입력을_검사한다() throws Exception {
        for (String path : List.of("/api/v1/rooms/home", "/api/v1/rooms/ready")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(user(ownerEmail).roles("USER"))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/rooms/home").with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liveRooms").isEmpty())
                .andExpect(jsonPath("$.data.readyRooms.rooms").isEmpty())
                .andExpect(jsonPath("$.data.readyRooms.nextCursor").value(nullValue()));
        mvc.perform(get("/api/v1/rooms/ready").with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rooms").isEmpty());
        mvc.perform(get("/api/v1/rooms/home").param("readyLimit", "0").with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(get("/api/v1/rooms/ready").param("cursor", "invalid").with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    private long readyRoomAt(String email, String createdAt) throws Exception {
        long roomId = createdData(createRoom(email)).path("roomId").asLong();
        jdbc.sql("UPDATE lecture_rooms SET created_at = ? WHERE id = ?")
                .params(createdAt, roomId).update();
        return roomId;
    }

    @Test
    @Transactional
    void 종료_기록은_강사별_종료시각과_ID_내림차순으로_열개씩_이어진다() throws Exception {
        long oldest = endedRoomAt(ownerEmail, "2026-11-21 08:00:00");
        long laterEnds = createdData(createRoom(ownerEmail)).required("roomId").asLong();
        var tied = new ArrayList<Long>();
        for (int i = 0; i < 11; i++) {
            tied.add(endedRoomAt(ownerEmail, "2026-11-21 09:00:00"));
        }
        endedRoomAt(otherEmail, "2026-11-21 13:00:00");
        createRoom(ownerEmail);
        liveRoomAt(ownerEmail, "2026-11-21 13:00:00");

        long completed = tied.getLast();
        jdbc.sql("""
                INSERT INTO lecture_reports (lecture_room_id, status, created_at)
                VALUES (?, 'COMPLETED', NOW())
                """).param(completed).update();
        var page = mvc.perform(get("/api/v1/rooms/history").with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.serverTime").isString())
                .andExpect(jsonPath("$.data.totalCount").value(12))
                .andExpect(jsonPath("$.data.rooms.length()").value(10))
                .andExpect(jsonPath("$.data.rooms[0].roomId").value(completed))
                .andExpect(jsonPath("$.data.rooms[0].endedAt").value("2026-11-21T00:00:00Z"))
                .andExpect(jsonPath("$.data.rooms[0].reportStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.rooms[1].reportStatus").value(nullValue()))
                .andExpect(jsonPath("$.data.rooms[9].roomId").value(tied.get(1).intValue()))
                .andReturn();
        String cursor = createdData(page).required("nextCursor").asText();
        // A previously created room ends in the same second as the newest one.
        jdbc.sql("UPDATE lecture_rooms SET status = 'ENDED', ended_at = '2026-11-21 09:00:00' WHERE id = ?")
                .param(laterEnds).update();
        mvc.perform(get("/api/v1/rooms/history").param("cursor", cursor).with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rooms.length()").value(3))
                .andExpect(jsonPath("$.data.rooms[0].roomId").value(tied.getFirst().intValue()))
                .andExpect(jsonPath("$.data.rooms[1].roomId").value(laterEnds))
                .andExpect(jsonPath("$.data.rooms[2].roomId").value(oldest))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()));
        mvc.perform(get("/api/v1/rooms/history/latest").with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(13));
    }

    @Test
    @Transactional
    void 이력의_상태_갱신은_지정한_내_종료방만_반환하며_미접수도_포함한다() throws Exception {
        var missing = endedRoomAt(ownerEmail, "2026-11-21 09:00:00");
        var pending = endedRoomAt(ownerEmail, "2026-11-21 09:00:00");
        var other = endedRoomAt(otherEmail, "2026-11-21 09:00:00");
        var live = liveRoomAt(ownerEmail, "2026-11-21 09:00:00");
        endedRoomAt(ownerEmail, "2026-11-21 09:00:00");
        jdbc.sql("INSERT INTO lecture_reports (lecture_room_id, status, created_at) VALUES (?, 'PENDING', NOW())")
                .param(pending).update();
        var response = mvc.perform(get("/api/v1/rooms/history/statuses").with(user(ownerEmail).roles("HOST"))
                        .param("roomIds", missing + "," + other + "," + pending + "," + live + "," + Long.MAX_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.serverTime").isString())
                .andReturn();
        var rows = createdData(response).required("rooms");
        assertThat(rows).hasSize(2);
        assertThat(rows).anyMatch(row -> row.required("roomId").asLong() == missing && row.required("reportStatus").isNull())
                .anyMatch(row -> row.required("roomId").asLong() == pending && row.required("reportStatus").asText().equals("PENDING"));
        jdbc.sql("UPDATE lecture_reports SET status = 'COMPLETED' WHERE lecture_room_id = ?").param(pending).update();
        mvc.perform(get("/api/v1/rooms/history/statuses").with(user(ownerEmail).roles("HOST"))
                        .param("roomIds", Long.toString(pending)))
                .andExpect(jsonPath("$.data.rooms[0].reportStatus").value("COMPLETED"));
    }

    @Test
    @Transactional
    void 이력_조회는_빈_목록과_권한_및_잘못된_요청을_구별한다() throws Exception {
        for (String path : List.of("/api/v1/rooms/history", "/api/v1/rooms/history/latest", "/api/v1/rooms/history/statuses")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(user(ownerEmail).roles("USER"))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/rooms/history").with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(0))
                .andExpect(jsonPath("$.data.rooms").isEmpty())
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()));
        mvc.perform(get("/api/v1/rooms/history").with(user(ownerEmail).roles("HOST")).param("cursor", "broken"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        for (String ids : List.of("", "0", "wrong", "1,".repeat(50) + "1")) {
            mvc.perform(get("/api/v1/rooms/history/statuses").with(user(ownerEmail).roles("HOST")).param("roomIds", ids))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
    }

    @Test
    @Transactional
    void 종료_시각이_없는_기록도_맨_뒤에_남으며_페이지_경계를_넘을_수_있다() throws Exception {
        long oldestUndated = endedRoomAt(ownerEmail, null);
        for (int i = 0; i < 9; i++) {
            endedRoomAt(ownerEmail, null);
        }
        long dated = endedRoomAt(ownerEmail, "2026-11-21 09:00:00");
        var first = mvc.perform(get("/api/v1/rooms/history").with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rooms[0].roomId").value(dated))
                .andExpect(jsonPath("$.data.rooms[9].endedAt").value(nullValue()))
                .andReturn();
        mvc.perform(get("/api/v1/rooms/history").with(user(ownerEmail).roles("HOST"))
                        .param("cursor", createdData(first).required("nextCursor").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rooms.length()").value(1))
                .andExpect(jsonPath("$.data.rooms[0].roomId").value(oldestUndated));
    }

    private long endedRoomAt(String email, String endedAt) throws Exception {
        long roomId = createdData(createRoom(email)).required("roomId").asLong();
        jdbc.sql("UPDATE lecture_rooms SET status = 'ENDED', ended_at = ? WHERE id = ?")
                .param(endedAt).param(roomId).update();
        return roomId;
    }

    @Test
    void 소유자는_기존_참여코드로_초대링크를_조회한다()
            throws Exception {

        JsonNode created = createdData(createRoom(ownerEmail));

        long roomId = created.path("roomId").asLong();
        String joinCode = created.path("joinCode").asText();
        String expectedUrl = "https://react.example/join/" + joinCode;

        mvc.perform(get("/api/v1/rooms/{roomId}/invitation", roomId)
                        .with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").value(roomId))
                .andExpect(jsonPath("$.data.joinCode").value(joinCode))
                .andExpect(jsonPath("$.data.joinUrl").value(expectedUrl))
                .andExpect(jsonPath("$.data.qrText").value(expectedUrl));
    }

    @Test
    void 다른_강연자는_초대정보를_조회할_수_없다()
            throws Exception {

        JsonNode created = createdData(createRoom(ownerEmail));
        long roomId = created.path("roomId").asLong();

        mvc.perform(get("/api/v1/rooms/{roomId}/invitation", roomId)
                        .with(user(otherEmail).roles("HOST")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 인증되지_않은_요청은_거부한다()
            throws Exception {

        mvc.perform(post("/api/v1/rooms")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"인증 없는 강연방"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void HOST_권한이_없으면_생성할_수_없다()
            throws Exception {

        mvc.perform(post("/api/v1/rooms")
                        .with(user(ownerEmail).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"권한 없는 강연방"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 빈_제목은_거부한다()
            throws Exception {

        mvc.perform(post("/api/v1/rooms")
                        .with(user(ownerEmail).roles("HOST"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void 존재하지_않는_강연방은_404를_반환한다()
            throws Exception {

        mvc.perform(get("/api/v1/rooms/{roomId}/invitation", Long.MAX_VALUE)
                        .with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
    }

    @Test
    void 소유자는_자신의_강연방_상세정보를_조회한다()
            throws Exception {

        JsonNode created = createdData(createRoom(ownerEmail));

        long roomId = created.path("roomId").asLong();
        String joinCode = created.path("joinCode").asText();

        mvc.perform(get("/api/v1/rooms/{roomId}", roomId)
                        .with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").value(roomId))
                .andExpect(jsonPath("$.data.title").value("Redis 실습 강연"))
                .andExpect(jsonPath("$.data.description").value("Redis와 MySQL의 역할"))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.joinCode").value(joinCode))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty());
    }

    @Test
    void 다른_강연자는_강연방_상세정보를_조회할_수_없다()
            throws Exception {

        JsonNode created = createdData(createRoom(ownerEmail));
        long roomId = created.path("roomId").asLong();

        mvc.perform(get("/api/v1/rooms/{roomId}", roomId)
                        .with(user(otherEmail).roles("HOST")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 로그인하지_않으면_상세정보를_조회할_수_없다()
            throws Exception {

        JsonNode created = createdData(createRoom(ownerEmail));
        long roomId = created.path("roomId").asLong();

        mvc.perform(get("/api/v1/rooms/{roomId}", roomId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 존재하지_않는_강연방의_상세조회는_404를_반환한다()
            throws Exception {

        mvc.perform(get("/api/v1/rooms/{roomId}", Long.MAX_VALUE)
                        .with(user(ownerEmail).roles("HOST")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
    }

    @Test
    void 공개조회는_인증없이_READY_강연방을_조회한다()
            throws Exception {

        JsonNode created = createdData(createRoom(ownerEmail));

        long roomId = created.path("roomId").asLong();
        String joinCode = created.path("joinCode").asText();

        mvc.perform(get("/api/v1/public/rooms/{joinCode}", joinCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").value(roomId))
                .andExpect(jsonPath("$.data.title").value("Redis 실습 강연"))
                .andExpect(jsonPath("$.data.description").value("Redis와 MySQL의 역할"))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.canJoin").value(true))
                .andExpect(jsonPath("$.data.host").doesNotExist())
                .andExpect(jsonPath("$.data.hostUserId").doesNotExist())
                .andExpect(jsonPath("$.data.joinCode").doesNotExist());
    }

    @Test
    void LIVE_강연방은_청중_입장이_가능하다()
            throws Exception {

        JsonNode created = createdData(createRoom(ownerEmail));

        long roomId = created.path("roomId").asLong();
        String joinCode = created.path("joinCode").asText();

        jdbc.sql("""
                UPDATE lecture_rooms
                SET status = 'LIVE'
                WHERE id = ?
                """)
                .param(roomId)
                .update();

        mvc.perform(get("/api/v1/public/rooms/{joinCode}", joinCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("LIVE"))
                .andExpect(jsonPath("$.data.canJoin").value(true));
    }

    @Test
    void 종료된_강연방은_공개조회되지만_입장은_불가능하다()
            throws Exception {

        JsonNode created = createdData(createRoom(ownerEmail));

        long roomId = created.path("roomId").asLong();
        String joinCode = created.path("joinCode").asText();

        jdbc.sql("""
                UPDATE lecture_rooms
                SET status = 'ENDED'
                WHERE id = ?
                """)
                .param(roomId)
                .update();

        mvc.perform(get("/api/v1/public/rooms/{joinCode}", joinCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ENDED"))
                .andExpect(jsonPath("$.data.canJoin").value(false));
    }

    @Test
    void 잘못된_참여코드는_404를_반환한다()
            throws Exception {

        mvc.perform(get("/api/v1/public/rooms/{joinCode}", "INVALIDCODE123"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVALID_JOIN_CODE"));
    }

    private MvcResult createRoom(String email) throws Exception {

        String body = json.writeValueAsString(Map.of(
                "title", "Redis 실습 강연",
                "description", "Redis와 MySQL의 역할"
        ));

        return mvc.perform(post("/api/v1/rooms")
                        .with(user(email).roles("HOST"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private JsonNode createdData(MvcResult result) throws Exception {
        return json.readTree(
                result.getResponse().getContentAsString()
        ).path("data");
    }
}
