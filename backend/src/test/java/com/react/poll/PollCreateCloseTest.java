package com.react.poll;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
class PollCreateCloseTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    private String hostEmail;
    private long hostUserId;
    private long room;

    @BeforeEach
    void prepareRoom() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        hostEmail = UUID.randomUUID() + "@example.com";
        hostUserId = newUser(hostEmail);
        room = newRoom(hostUserId, "LIVE");
    }

    @Test
    void createsGeneralPollWithDefaultDuration() throws Exception {
        mvc.perform(createPoll(room, host(hostEmail), """
                        { "question": " Redis가 필요한 이유를 이해했나요? ", "options": ["이해했어요", "조금 헷갈려요", "다시 설명이 필요해요"] }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.roomId").value(room))
                .andExpect(jsonPath("$.data.type").value("GENERAL"))
                .andExpect(jsonPath("$.data.question").value("Redis가 필요한 이유를 이해했나요?"))
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.durationSeconds").value(60))
                .andExpect(jsonPath("$.data.options.length()").value(3))
                .andExpect(jsonPath("$.data.options[0].content").value("이해했어요"))
                .andExpect(jsonPath("$.data.options[0].displayOrder").value(1))
                .andExpect(jsonPath("$.data.options[0].understandingStatus").doesNotExist())
                .andExpect(jsonPath("$.data.options[2].displayOrder").value(3))
                .andExpect(jsonPath("$.data.closedAt").doesNotExist())
                .andExpect(jsonPath("$.data.closeReason").doesNotExist())
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.closesAt").exists())
                .andExpect(jsonPath("$.data.serverTime").exists());
        assertThat(count("SELECT COUNT(*) FROM polls WHERE lecture_room_id = ? AND status = 'OPEN' AND type = 'GENERAL'")).isEqualTo(1);
    }

    @Test
    void createsUnderstandingCheckWithFixedOptions() throws Exception {
        // 클라이언트가 보낸 선택지와 질문 형식은 선택지에 영향을 주지 않는다.
        mvc.perform(createPoll(room, host(hostEmail), """
                        { "type": "UNDERSTANDING", "options": ["가짜 선택지"], "durationSeconds": 30 }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.type").value("UNDERSTANDING"))
                .andExpect(jsonPath("$.data.question").value("지금 설명을 이해하셨나요?"))
                .andExpect(jsonPath("$.data.durationSeconds").value(30))
                .andExpect(jsonPath("$.data.options.length()").value(3))
                .andExpect(jsonPath("$.data.options[0].content").value("이해됨"))
                .andExpect(jsonPath("$.data.options[0].understandingStatus").value("UNDERSTOOD"))
                .andExpect(jsonPath("$.data.options[1].understandingStatus").value("CONFUSED"))
                .andExpect(jsonPath("$.data.options[2].content").value("모르겠음"))
                .andExpect(jsonPath("$.data.options[2].understandingStatus").value("NOT_UNDERSTOOD"));
    }

    @Test
    void closesAtIsCreatedAtPlusDuration() throws Exception {
        mvc.perform(createPoll(room, host(hostEmail), """
                        { "type": "UNDERSTANDING", "durationSeconds": 90 }
                        """))
                .andExpect(status().isCreated());
        var row = jdbc.sql("SELECT TIMESTAMPDIFF(SECOND, created_at, closes_at) FROM polls WHERE lecture_room_id = ?")
                .param(room).query(Long.class).single();
        assertThat(row).isEqualTo(90);
    }

    @Test
    void rejectsInvalidRequests() throws Exception {
        List<String> bodies = List.of(
                "{ \"type\": \"UNDERSTANDING\", \"durationSeconds\": 9 }",
                "{ \"type\": \"UNDERSTANDING\", \"durationSeconds\": 301 }",
                "{ \"type\": \"UNDERSTANDING\", \"durationSeconds\": 0 }",
                "{ \"type\": \"UNKNOWN\" }",
                "{ \"options\": [\"가\", \"나\"] }",
                "{ \"question\": \"   \", \"options\": [\"가\", \"나\"] }",
                "{ \"question\": \"질문\" }",
                "{ \"question\": \"질문\", \"options\": [\"하나\"] }",
                "{ \"question\": \"질문\", \"options\": [\"1\", \"2\", \"3\", \"4\", \"5\", \"6\"] }",
                "{ \"question\": \"질문\", \"options\": [\"가\", \"  \"] }",
                "{ \"question\": \"질문\", \"options\": [\"가\", \"가\"] }",
                "{ \"question\": \"" + "가".repeat(501) + "\", \"options\": [\"가\", \"나\"] }",
                "{ \"question\": \"질문\", \"options\": [\"가\", \"" + "나".repeat(256) + "\"] }");
        for (String body : bodies) {
            mvc.perform(createPoll(room, host(hostEmail), body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        assertThat(count("SELECT COUNT(*) FROM polls WHERE lecture_room_id = ?")).isZero();
    }

    @Test
    void onlyLiveRoomAcceptsPolls() throws Exception {
        long ready = newRoom(hostUserId, "READY");
        long ended = newRoom(hostUserId, "ENDED");

        mvc.perform(createPoll(ready, host(hostEmail), "{ \"type\": \"UNDERSTANDING\" }"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROOM_NOT_LIVE"));
        mvc.perform(createPoll(ended, host(hostEmail), "{ \"type\": \"UNDERSTANDING\" }"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROOM_ENDED"));
    }

    @Test
    void secondPollIsRejectedWhileFirstIsStillOpen() throws Exception {
        mvc.perform(createPoll(room, host(hostEmail), "{ \"type\": \"UNDERSTANDING\" }")).andExpect(status().isCreated());

        // 일반 투표든 이해도 체크든 열린 투표가 있으면 새로 만들 수 없다.
        mvc.perform(createPoll(room, host(hostEmail), "{ \"type\": \"UNDERSTANDING\" }"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLL_ALREADY_OPEN"));
        mvc.perform(createPoll(room, host(hostEmail), "{ \"question\": \"질문\", \"options\": [\"가\", \"나\"] }"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLL_ALREADY_OPEN"));
        assertThat(count("SELECT COUNT(*) FROM polls WHERE lecture_room_id = ?")).isEqualTo(1);
    }

    @Test
    void otherRoomsPollDoesNotBlockCreation() throws Exception {
        long otherRoom = newRoom(newUser(UUID.randomUUID() + "@example.com"), "LIVE");
        insertPoll(otherRoom, "OPEN", LocalDateTime.now(SEOUL), LocalDateTime.now(SEOUL).plusMinutes(5));

        mvc.perform(createPoll(room, host(hostEmail), "{ \"type\": \"UNDERSTANDING\" }")).andExpect(status().isCreated());
    }

    @Test
    void expiredOpenPollIsClosedByTimeoutWhenCreatingNewOne() throws Exception {
        LocalDateTime createdAt = LocalDateTime.now(SEOUL).minusMinutes(3).withNano(0);
        LocalDateTime closesAt = createdAt.plusSeconds(60);
        long expired = insertPoll(room, "OPEN", createdAt, closesAt);

        // 스케줄러가 아직 마감하지 못한 만료 투표 때문에 새 투표가 막히지 않는다.
        mvc.perform(createPoll(room, host(hostEmail), "{ \"type\": \"UNDERSTANDING\" }")).andExpect(status().isCreated());

        var row = jdbc.sql("SELECT status, close_reason, closed_at, closes_at FROM polls WHERE id = ?").param(expired)
                .query((rs, n) -> new Object[]{rs.getString(1), rs.getString(2),
                        rs.getObject(3, LocalDateTime.class), rs.getObject(4, LocalDateTime.class)}).single();
        assertThat(row[0]).isEqualTo("CLOSED");
        assertThat(row[1]).isEqualTo("TIMEOUT");
        assertThat(row[2]).isEqualTo(closesAt);
        assertThat(row[3]).isEqualTo(closesAt);
        assertThat(count("SELECT COUNT(*) FROM polls WHERE lecture_room_id = ? AND status = 'OPEN'")).isEqualTo(1);
    }

    @Test
    void hostClosesOpenPollOnce() throws Exception {
        long pollId = createUnderstanding();

        mvc.perform(closePoll(room, pollId, host(hostEmail)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pollId").value(pollId))
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.closeReason").value("HOST"))
                .andExpect(jsonPath("$.data.closedAt").exists());
        mvc.perform(closePoll(room, pollId, host(hostEmail)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLL_ALREADY_CLOSED"));
        // 마감하면 새 투표를 만들 수 있다.
        mvc.perform(createPoll(room, host(hostEmail), "{ \"type\": \"UNDERSTANDING\" }")).andExpect(status().isCreated());
    }

    @Test
    void closingExpiredPollIsAlreadyClosed() throws Exception {
        LocalDateTime createdAt = LocalDateTime.now(SEOUL).minusMinutes(3).withNano(0);
        long expired = insertPoll(room, "OPEN", createdAt, createdAt.plusSeconds(60));

        mvc.perform(closePoll(room, expired, host(hostEmail)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLL_ALREADY_CLOSED"));
    }

    @Test
    void closeRejectsUnknownOrForeignPoll() throws Exception {
        long otherRoom = newRoom(hostUserId, "LIVE");
        long foreign = insertPoll(otherRoom, "OPEN", LocalDateTime.now(SEOUL), LocalDateTime.now(SEOUL).plusMinutes(5));

        mvc.perform(closePoll(room, foreign, host(hostEmail)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POLL_NOT_FOUND"));
        mvc.perform(closePoll(room, Long.MAX_VALUE, host(hostEmail)))
                .andExpect(status().isNotFound());
    }

    @Test
    void listsPollsNewestFirstAndFiltersByEffectiveStatus() throws Exception {
        LocalDateTime now = LocalDateTime.now(SEOUL).withNano(0);
        long closed = insertPoll(room, "CLOSED", now.minusMinutes(10), now.minusMinutes(9));
        long expiredOpen = insertPoll(room, "OPEN", now.minusMinutes(5), now.minusMinutes(4));
        long open = insertPoll(room, "OPEN", now.minusSeconds(5), now.plusMinutes(5));

        mvc.perform(get(listUrl(room)).with(authentication(host(hostEmail))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.polls.length()").value(3))
                .andExpect(jsonPath("$.data.polls[0].pollId").value(open))
                .andExpect(jsonPath("$.data.polls[0].status").value("OPEN"))
                // status 가 OPEN 으로 남아 있어도 마감 시각이 지났으면 TIMEOUT 으로 마감된 것으로 보인다.
                .andExpect(jsonPath("$.data.polls[1].pollId").value(expiredOpen))
                .andExpect(jsonPath("$.data.polls[1].status").value("CLOSED"))
                .andExpect(jsonPath("$.data.polls[1].closeReason").value("TIMEOUT"))
                .andExpect(jsonPath("$.data.polls[2].pollId").value(closed))
                .andExpect(jsonPath("$.data.serverTime").exists());
        mvc.perform(get(listUrl(room) + "?status=OPEN").with(authentication(host(hostEmail))))
                .andExpect(jsonPath("$.data.polls.length()").value(1))
                .andExpect(jsonPath("$.data.polls[0].pollId").value(open));
        mvc.perform(get(listUrl(room) + "?status=CLOSED").with(authentication(host(hostEmail))))
                .andExpect(jsonPath("$.data.polls.length()").value(2));
        mvc.perform(get(listUrl(room) + "?status=UNKNOWN").with(authentication(host(hostEmail))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listIncludesOptionsInOrder() throws Exception {
        createUnderstanding();

        mvc.perform(get(listUrl(room)).with(authentication(host(hostEmail))))
                .andExpect(jsonPath("$.data.polls[0].type").value("UNDERSTANDING"))
                .andExpect(jsonPath("$.data.polls[0].options[0].understandingStatus").value("UNDERSTOOD"))
                .andExpect(jsonPath("$.data.polls[0].options[2].understandingStatus").value("NOT_UNDERSTOOD"));
    }

    @Test
    void onlyRoomOwnerWithHostRoleCanUsePolls() throws Exception {
        long pollId = createUnderstanding();
        var participantRole = UsernamePasswordAuthenticationToken.authenticated(
                hostEmail, null, List.of(new SimpleGrantedAuthority("ROLE_PARTICIPANT")));
        var stranger = host("other-" + UUID.randomUUID() + "@example.com");

        // 로그인하지 않으면 401
        mvc.perform(post(listUrl(room)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(listUrl(room))).andExpect(status().isUnauthorized());
        // 다른 강연자나 HOST 역할이 아닌 사용자는 403
        for (var auth : List.of(stranger, participantRole)) {
            mvc.perform(createPoll(room, auth, "{ \"type\": \"UNDERSTANDING\" }")).andExpect(status().isForbidden());
            mvc.perform(closePoll(room, pollId, auth)).andExpect(status().isForbidden());
            mvc.perform(get(listUrl(room)).with(authentication(auth))).andExpect(status().isForbidden());
        }
        // 없는 강연방은 404
        mvc.perform(createPoll(Long.MAX_VALUE, host(hostEmail), "{ \"type\": \"UNDERSTANDING\" }"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
    }

    private long createUnderstanding() throws Exception {
        var result = mvc.perform(createPoll(room, host(hostEmail), "{ \"type\": \"UNDERSTANDING\" }"))
                .andExpect(status().isCreated()).andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.data.pollId")).longValue();
    }

    private static MockHttpServletRequestBuilder createPoll(long roomId, UsernamePasswordAuthenticationToken auth, String body) {
        return post(listUrl(roomId)).with(csrf()).with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder closePoll(long roomId, long pollId, UsernamePasswordAuthenticationToken auth) {
        return post(listUrl(roomId) + "/" + pollId + "/close").with(csrf()).with(authentication(auth));
    }

    private static String listUrl(long roomId) {
        return "/api/v1/rooms/" + roomId + "/polls";
    }

    private static UsernamePasswordAuthenticationToken host(String email) {
        return UsernamePasswordAuthenticationToken.authenticated(email, null, List.of(new SimpleGrantedAuthority("ROLE_HOST")));
    }

    private long count(String sql) {
        return jdbc.sql(sql).param(room).query(Long.class).single();
    }

    private long newUser(String email) {
        return insert("INSERT INTO users(email,password_hash,name,created_at) VALUES (?, 'hash', '강연자', ?)",
                email, LocalDateTime.now(SEOUL));
    }

    private long newRoom(long hostId, String status) {
        return insert("""
                INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at)
                VALUES (?, 'test', ?, ?, ?)
                """, hostId, UUID.randomUUID().toString(), status, LocalDateTime.now(SEOUL));
    }

    private long insertPoll(long roomId, String status, LocalDateTime createdAt, LocalDateTime closesAt) {
        return insert("""
                INSERT INTO polls(lecture_room_id,type,question,status,created_at,closes_at)
                VALUES (?, 'GENERAL', '질문', ?, ?, ?)
                """, roomId, status, createdAt, closesAt);
    }

    private long insert(String sql, Object... params) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql(sql).params(params).update(keys);
        return keys.getKey().longValue();
    }
}
