package com.react.poll;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.poll.dto.CreatePollRequest;
import com.react.poll.dto.PollView;
import com.react.poll.entity.PollType;
import com.react.poll.service.PollService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 결과 조회 API 는 강연방 소유자만 볼 수 있고, 청중 개개인의 선택은 내려주지 않는다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PollResultApiTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired PollService service;
    private String hostEmail;
    private long room;
    private PollView poll;

    @BeforeEach
    void prepare() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        hostEmail = UUID.randomUUID() + "@example.com";
        long host = insert("INSERT INTO users(email,password_hash,name,created_at) VALUES (?, 'hash', '강연자', ?)",
                hostEmail, LocalDateTime.now(SEOUL));
        room = insert("""
                INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at)
                VALUES (?, 'test', ?, 'LIVE', ?)
                """, host, UUID.randomUUID().toString(), LocalDateTime.now(SEOUL));
        poll = service.create(room, hostEmail, new CreatePollRequest(PollType.UNDERSTANDING, null, null, 60));
    }

    @Test
    void 방_소유자는_결과를_조회하고_청중_개개인의_정보는_없다() throws Exception {
        long participant = insert("""
                INSERT INTO lecture_participants(lecture_room_id,nickname,participant_token_hash,joined_at)
                VALUES (?, '청중', ?, ?)
                """, room, UUID.randomUUID().toString(), LocalDateTime.now(SEOUL));
        service.respond(room, poll.pollId(), participant, poll.options().get(0).optionId());

        String body = mvc.perform(get(url()).with(authentication(host(hostEmail))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = json.readTree(body).path("data");
        assertThat(data.path("pollId").asLong()).isEqualTo(poll.pollId());
        assertThat(data.path("type").asText()).isEqualTo("UNDERSTANDING");
        assertThat(data.path("status").asText()).isEqualTo("OPEN");
        assertThat(data.path("totalParticipants").asLong()).isEqualTo(1);
        assertThat(data.path("respondedParticipants").asLong()).isEqualTo(1);
        assertThat(data.path("unrespondedParticipants").asLong()).isZero();
        assertThat(data.path("responseRate").asDouble()).isEqualTo(100.0);
        assertThat(data.path("options")).hasSize(3);
        assertThat(data.path("options").get(0).path("understandingStatus").asText()).isEqualTo("UNDERSTOOD");
        assertThat(data.path("options").get(0).path("count").asLong()).isEqualTo(1);
        assertThat(data.path("calculatedAt").asText()).isNotBlank();
        assertThat(body).doesNotContain("participantId").doesNotContain("nickname");
    }

    @Test
    void 로그인하지_않으면_401이다() throws Exception {
        mvc.perform(get(url())).andExpect(status().isUnauthorized());
    }

    @Test
    void 청중_역할은_조회할_수_없다() throws Exception {
        var participantRole = UsernamePasswordAuthenticationToken.authenticated(
                "audience", null, List.of(new SimpleGrantedAuthority("ROLE_PARTICIPANT")));

        mvc.perform(get(url()).with(authentication(participantRole))).andExpect(status().isForbidden());
    }

    @Test
    void 다른_강연자는_내_방의_결과를_조회할_수_없다() throws Exception {
        String stranger = UUID.randomUUID() + "@example.com";
        insert("INSERT INTO users(email,password_hash,name,created_at) VALUES (?, 'hash', '다른 강연자', ?)",
                stranger, LocalDateTime.now(SEOUL));

        mvc.perform(get(url()).with(authentication(host(stranger)))).andExpect(status().isForbidden());
    }

    @Test
    void 없는_투표는_404이다() throws Exception {
        mvc.perform(get("/api/v1/rooms/" + room + "/polls/" + (poll.pollId() + 99_999) + "/results")
                        .with(authentication(host(hostEmail))))
                .andExpect(status().isNotFound());
    }

    private String url() {
        return "/api/v1/rooms/" + room + "/polls/" + poll.pollId() + "/results";
    }

    private static UsernamePasswordAuthenticationToken host(String email) {
        return UsernamePasswordAuthenticationToken.authenticated(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_HOST")));
    }

    private long insert(String sql, Object... params) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql(sql).params(params).update(keys);
        return keys.getKey().longValue();
    }
}
