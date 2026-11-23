package com.react.poll;

import com.react.poll.dto.CreatePollRequest;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PollMixedCookieApiTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired PollService polls;

    private String hostAEmail;
    private String hostBEmail;
    private long roomA;
    private long roomB;
    private long pollId;

    @BeforeEach
    void prepare() {
        assertThat(jdbc.sql("SELECT DATABASE()")
                .query(String.class).single()).endsWith("_test");

        Flyway.configure().dataSource(dataSource).load().migrate();

        hostAEmail = UUID.randomUUID() + "@example.com";
        hostBEmail = UUID.randomUUID() + "@example.com";

        long hostA = newHost(hostAEmail);
        long hostB = newHost(hostBEmail);

        roomA = newRoom(hostA);
        roomB = newRoom(hostB);

        pollId = polls.create(
                roomB,
                hostBEmail,
                new CreatePollRequest(
                        PollType.UNDERSTANDING, null, null, 60
                )
        ).pollId();
    }

    @Test
    void 다른_방_HOST도_대상_방의_청중_쿠키가_있으면_조회할_수_있다()
            throws Exception {

        Cookie participantCookie = join(roomB);

        mvc.perform(get("/api/v1/rooms/{roomId}/polls", roomB)
                        .with(authentication(host(hostAEmail)))
                        .cookie(participantCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.polls[0].pollId")
                        .value(pollId));
    }

    @Test
    void 다른_방_HOST가_청중_쿠키_없이_조회하면_403이다()
            throws Exception {

        mvc.perform(get("/api/v1/rooms/{roomId}/polls", roomB)
                        .with(authentication(host(hostAEmail))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 다른_방의_청중_쿠키만으로는_조회할_수_없다()
            throws Exception {

        Cookie wrongRoomCookie = join(roomA);

        mvc.perform(get("/api/v1/rooms/{roomId}/polls", roomB)
                        .with(authentication(host(hostAEmail)))
                        .cookie(wrongRoomCookie))
                .andExpect(status().isForbidden());
    }

    @Test
    void 다른_방_토큰을_대상_방_쿠키로_위조해도_조회할_수_없다()
            throws Exception {

        Cookie otherRoomCookie = join(roomA);

        Cookie forged = new Cookie(
                "PARTICIPANT_SESSION_" + roomB,
                otherRoomCookie.getValue()
        );

        mvc.perform(get("/api/v1/rooms/{roomId}/polls", roomB)
                        .with(authentication(host(hostAEmail)))
                        .cookie(forged))
                .andExpect(status().isForbidden());
    }

    @Test
    void 방_소유자는_청중_쿠키_없이도_조회할_수_있다()
            throws Exception {

        mvc.perform(get("/api/v1/rooms/{roomId}/polls", roomB)
                        .with(authentication(host(hostBEmail))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.polls[0].pollId")
                        .value(pollId));
    }

    private Cookie join(long roomId) throws Exception {
        String code = jdbc.sql("""
                SELECT join_code
                FROM lecture_rooms
                WHERE id = ?
                """)
                .param(roomId)
                .query(String.class)
                .single();

        MvcResult result = mvc.perform(
                post("/api/v1/public/rooms/{joinCode}/participants", code)
                        .with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"청중\"}")
        ).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);

        String name = "PARTICIPANT_SESSION_" + roomId;
        String setCookie = result.getResponse()
                .getHeader(HttpHeaders.SET_COOKIE);

        assertThat(setCookie).isNotNull();

        String value = setCookie.split(";", 2)[0]
                .substring(name.length() + 1);

        return new Cookie(name, value);
    }

    private long newHost(String email) {
        return insert("""
                INSERT INTO users(email, password_hash, name, created_at)
                VALUES (?, 'hash', '강연자', ?)
                """, email, LocalDateTime.now(SEOUL));
    }

    private long newRoom(long hostId) {
        return insert("""
                INSERT INTO lecture_rooms(
                    host_user_id, title, join_code, status, created_at
                )
                VALUES (?, 'test', ?, 'LIVE', ?)
                """,
                hostId,
                UUID.randomUUID().toString()
                        .replace("-", "")
                        .substring(0, 12)
                        .toUpperCase(),
                LocalDateTime.now(SEOUL));
    }

    private long insert(String sql, Object... params) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql(sql).params(params).update(keys);
        return keys.getKey().longValue();
    }

    private static UsernamePasswordAuthenticationToken host(String email) {
        return UsernamePasswordAuthenticationToken.authenticated(
                email,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_HOST"))
        );
    }
}
