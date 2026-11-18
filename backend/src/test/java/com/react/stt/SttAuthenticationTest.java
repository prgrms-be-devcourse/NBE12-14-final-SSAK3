package com.react.stt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.stt.dto.SttGap;
import com.react.stt.dto.SttSegmentRequest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SttAuthenticationTest {
    private static final String PASSWORD = "SttTestPassword123!";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired PasswordEncoder encoder;
    private final String email = UUID.randomUUID() + "@example.com";
    private final UUID key = UUID.randomUUID();
    private long room;

    enum Endpoint { START, STOP, SEGMENT, OPEN_GAP, CLOSE_GAP, QUERY }

    @BeforeEach
    void prepareRoom() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc.sql("INSERT INTO users(email,password_hash,name,created_at) VALUES (?, 'hash','host', NOW())")
                .param(email).update();
        jdbc.sql("""
                INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at)
                SELECT id,'auth test',?,'LIVE',NOW() FROM users WHERE email=?
                """).params(email, email).update();
        room = jdbc.sql("SELECT id FROM lecture_rooms WHERE join_code=?").param(email).query(Long.class).single();
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    void everyEndpointRequiresAuthenticatedHostAndRoomOwnership(Endpoint endpoint) throws Exception {
        mvc.perform(request(endpoint)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        // The same owner's email must not grant access without the HOST role.
        mvc.perform(request(endpoint).with(user(email).roles("USER"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(request(endpoint).with(user("other@example.com").roles("HOST")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM stt_sessions WHERE lecture_room_id=?")
                .param(room).query(Long.class).single()).isZero();
    }

    @Test
    void actualLoginSessionCanRecordQueryAndLogout() throws Exception {
        jdbc.sql("UPDATE users SET password_hash=? WHERE email=?").params(encoder.encode(PASSWORD), email).update();
        MockHttpSession session = login(email);
        var started = mvc.perform(startRequest().session(session)).andExpect(status().isCreated())
                .andReturn().getResponse();
        var data = json.readTree(started.getContentAsString()).path("data");
        long id = data.path("sttSessionId").asLong();
        Instant time = Instant.parse(data.path("startedAt").asText());
        String path = "/api/v1/rooms/" + room + "/stt/sessions/" + id;
        mvc.perform(post(path + "/segments").with(csrf()).session(session).contentType("application/json")
                        .content(json.writeValueAsBytes(new SttSegmentRequest(key, "one", "발언", time, time))))
                .andExpect(status().isCreated());
        var opened = mvc.perform(post(path + "/gaps").with(csrf()).session(session).contentType("application/json")
                        .content(json.writeValueAsBytes(new SttGap.OpenRequest(key, "gap", SttGap.Reason.ERROR, time))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        long gap = json.readTree(opened.getContentAsString()).at("/data/gapId").asLong();
        mvc.perform(patch(path + "/gaps/" + gap + "/close").with(csrf()).session(session).contentType("application/json")
                        .content(json.writeValueAsBytes(new SttGap.CloseRequest(key, time))))
                .andExpect(status().isOk());
        mvc.perform(post(path + "/stop").with(csrf()).session(session).contentType("application/json")
                        .content("{\"reason\":\"MANUAL_STOP\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("STOPPED"));
        mvc.perform(request(Endpoint.QUERY).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.segments.length()").value(1))
                .andExpect(jsonPath("$.data.gaps.length()").value(1));
        mvc.perform(post("/api/v1/auth/logout").with(csrf()).session(session)).andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        // Replay the invalidated server session, rather than merely omitting authentication.
        mvc.perform(request(Endpoint.QUERY).session(session)).andExpect(status().isUnauthorized());
        mvc.perform(startRequest()).andExpect(status().isUnauthorized());
    }

    @Test
    void actualLoginOfAnotherHostCannotReadOrStartOwnersRoom() throws Exception {
        String other = UUID.randomUUID() + "@example.com";
        jdbc.sql("INSERT INTO users(email,password_hash,name,created_at) VALUES (?,?,'other',NOW())")
                .params(other, encoder.encode(PASSWORD)).update();
        MockHttpSession session = login(other);
        mvc.perform(startRequest().session(session)).andExpect(status().isForbidden());
        mvc.perform(request(Endpoint.QUERY).session(session)).andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM stt_sessions WHERE lecture_room_id=?")
                .param(room).query(Long.class).single()).isZero();
    }

    private MockHttpSession login(String loginEmail) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
                        .content(json.writeValueAsBytes(Map.of("email", loginEmail, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    private MockHttpServletRequestBuilder startRequest() throws Exception {
        return post("/api/v1/rooms/{room}/stt/sessions", room).with(csrf()).contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("startKey", key)));
    }

    private MockHttpServletRequestBuilder request(Endpoint endpoint) throws Exception {
        String path = "/api/v1/rooms/" + room + "/stt/sessions/1";
        Instant time = Instant.parse("2026-11-18T00:00:00Z");
        return switch (endpoint) {
            case START -> startRequest();
            case STOP -> post(path + "/stop").with(csrf()).contentType("application/json").content("{\"reason\":\"MANUAL_STOP\"}");
            case SEGMENT -> post(path + "/segments").with(csrf()).contentType("application/json")
                    .content(json.writeValueAsBytes(new SttSegmentRequest(key, "one", "발언", time, time)));
            case OPEN_GAP -> post(path + "/gaps").with(csrf()).contentType("application/json")
                    .content(json.writeValueAsBytes(new SttGap.OpenRequest(key, "gap", SttGap.Reason.ERROR, time)));
            case CLOSE_GAP -> patch(path + "/gaps/1/close").with(csrf()).contentType("application/json")
                    .content(json.writeValueAsBytes(new SttGap.CloseRequest(key, time)));
            case QUERY -> get("/api/v1/rooms/{room}/stt/segments", room);
        };
    }
}
