package com.react.poll;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.poll.dto.CreatePollRequest;
import com.react.poll.dto.PollView;
import com.react.poll.entity.PollType;
import com.react.poll.service.PollService;
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
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** 청중 투표 응답 API 가 방별 참여자 쿠키로만 참여자를 확인하는지 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PollRespondApiTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired PollService service;
    private long room;
    private String joinCode;
    private PollView poll;

    @BeforeEach
    void prepare() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        String hostEmail = UUID.randomUUID() + "@example.com";
        long host = insert("INSERT INTO users(email,password_hash,name,created_at) VALUES (?, 'hash', '강연자', ?)",
                hostEmail, LocalDateTime.now(SEOUL));
        room = newRoom(host);
        joinCode = joinCodeOf(room);
        poll = service.create(room, hostEmail, new CreatePollRequest(PollType.UNDERSTANDING, null, null, 60));
    }

    @Test
    void participantCookieRespondsAndChangesChoice() throws Exception {
        Cookie cookie = join(joinCode, room);

        MvcResult first = respond(room, poll.pollId(), optionId(0), cookie);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(first).path("changed").asBoolean()).isTrue();
        assertThat(data(first).path("optionId").asLong()).isEqualTo(optionId(0));

        MvcResult changed = respond(room, poll.pollId(), optionId(1), cookie);
        assertThat(data(changed).path("changed").asBoolean()).isTrue();
        assertThat(data(first).path("participantId").asLong()).isEqualTo(data(changed).path("participantId").asLong());

        MvcResult same = respond(room, poll.pollId(), optionId(1), cookie);
        assertThat(data(same).path("changed").asBoolean()).isFalse();
        assertThat(responses()).isEqualTo(1);
    }

    @Test
    void withoutCookieIsUnauthenticatedAndNothingIsSaved() throws Exception {
        MvcResult result = respond(room, poll.pollId(), optionId(0));

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(responses()).isZero();
    }

    @Test
    void cookieOfAnotherRoomIsRejected() throws Exception {
        long otherRoom = newRoom(jdbc.sql("SELECT host_user_id FROM lecture_rooms WHERE id = ?")
                .param(room).query(Long.class).single());
        Cookie other = join(joinCodeOf(otherRoom), otherRoom);
        // 다른 방 토큰을 이 방 쿠키 이름에 실어 보내도 참여자 소속이 달라 받지 않는다.
        Cookie forged = new Cookie("PARTICIPANT_SESSION_" + room, other.getValue());

        MvcResult result = respond(room, poll.pollId(), optionId(0), forged);

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(responses()).isZero();
    }

    @Test
    void missingOptionIdIsBadRequestAndRequestWithoutCsrfIsForbidden() throws Exception {
        Cookie cookie = join(joinCode, room);

        MvcResult missing = mvc.perform(put("/api/v1/rooms/{r}/polls/{p}/responses/me", room, poll.pollId())
                .with(csrf()).cookie(cookie).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);

        MvcResult noCsrf = mvc.perform(put("/api/v1/rooms/{r}/polls/{p}/responses/me", room, poll.pollId())
                .cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("optionId", optionId(0))))).andReturn();
        assertThat(noCsrf.getResponse().getStatus()).isEqualTo(403);
        assertThat(responses()).isZero();
    }

    private Cookie join(String code, long roomId) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/public/rooms/{joinCode}/participants", code)
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("nickname", "청중")))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String name = "PARTICIPANT_SESSION_" + roomId;
        String first = result.getResponse().getHeader(HttpHeaders.SET_COOKIE).split(";", 2)[0];
        return new Cookie(name, first.substring(name.length() + 1));
    }

    private MvcResult respond(long roomId, long pollId, long optionId, Cookie... cookies) throws Exception {
        var request = put("/api/v1/rooms/{r}/polls/{p}/responses/me", roomId, pollId)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("optionId", optionId)));
        if (cookies.length > 0) {
            request.cookie(cookies);
        }
        return mvc.perform(request).andReturn();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private long optionId(int index) {
        return poll.options().get(index).optionId();
    }

    private long responses() {
        return jdbc.sql("SELECT COUNT(*) FROM poll_responses WHERE poll_id = ?").param(poll.pollId())
                .query(Long.class).single();
    }

    private String joinCodeOf(long roomId) {
        return jdbc.sql("SELECT join_code FROM lecture_rooms WHERE id = ?").param(roomId).query(String.class).single();
    }

    private long newRoom(long host) {
        return insert("""
                INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at)
                VALUES (?, 'test', ?, 'LIVE', ?)
                """, host, UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                LocalDateTime.now(SEOUL));
    }

    private long insert(String sql, Object... params) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql(sql).params(params).update(keys);
        return keys.getKey().longValue();
    }
}
