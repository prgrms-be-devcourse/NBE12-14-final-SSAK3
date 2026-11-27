package com.react.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PollParticipantIntegrationTest {

    private static final ZoneId SEOUL =
            ZoneId.of("Asia/Seoul");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    private String hostEmail;
    private String joinCode;

    private long roomId;
    private long pollId;
    private long understoodOptionId;
    private long confusedOptionId;

    private record Participant(
            long id,
            Cookie cookie
    ) {}

    @BeforeEach
    void setUp() {
        assertThat(jdbc.sql("SELECT DATABASE()")
                .query(String.class)
                .single())
                .isEqualTo("react_test");

        Flyway.configure()
                .dataSource(dataSource)
                .load()
                .migrate();

        hostEmail = UUID.randomUUID() + "@example.com";

        long hostId = insert("""
                INSERT INTO users
                    (email, password_hash, name, created_at)
                VALUES (?, 'hash', '강연자', ?)
                """,
                hostEmail,
                LocalDateTime.now(SEOUL));

        joinCode = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 12)
                .toUpperCase();

        roomId = insert("""
                INSERT INTO lecture_rooms
                    (host_user_id, title, join_code,
                     status, created_at)
                VALUES (?, '통합 테스트', ?, 'LIVE', ?)
                """,
                hostId,
                joinCode,
                LocalDateTime.now(SEOUL));
    }

    @Test
    void 이해도_체크_도중_입장한_청중은_집계에_포함되고_마감_이후에는_결과가_유지된다()
            throws Exception {

        // 1. 최초 청중 3명 입장
        Participant first = join("청중1");
        Participant second = join("청중2");
        join("청중3");

        assertThat(participantCount())
                .isEqualTo(3);

        // 2. 강연자가 이해도 체크 시작
        createUnderstandingPoll();

        assertSummary(results(), 3, 0, 3);

        // 3. 청중 2명이 응답
        respond(first, understoodOptionId);
        respond(second, confusedOptionId);

        assertSummary(results(), 3, 2, 1);

        // 4. 이해도 체크 진행 도중 청중 2명 추가 입장
        join("늦게 입장한 청중1");
        join("늦게 입장한 청중2");

        assertThat(participantCount())
                .isEqualTo(5);

        JsonNode beforeClose = results();

        assertSummary(beforeClose, 5, 2, 3);

        assertThat(optionCount(
                beforeClose, understoodOptionId))
                .isEqualTo(1);

        assertThat(optionCount(
                beforeClose, confusedOptionId))
                .isEqualTo(1);

        // 5. 강연자가 이해도 체크 마감
        closePoll();

        JsonNode closed = results();

        assertThat(closed.path("status").asText())
                .isEqualTo("CLOSED");

        assertSummary(closed, 5, 2, 3);

        assertThat(responseCount())
                .isEqualTo(2);

        // 6. 마감 이후 청중 1명 추가 입장
        Participant late = join("마감 후 입장한 청중");

        assertThat(participantCount())
                .isEqualTo(6);

        // 실제 강연방 참여자는 6명이지만
        // 이전에 마감한 이해도 체크의 전체 인원은 5명이다.
        JsonNode afterLateJoin = results();

        assertSummary(afterLateJoin, 5, 2, 3);

        assertThat(afterLateJoin.path("options"))
                .isEqualTo(closed.path("options"));

        // 7. 마감된 이해도 체크에 응답하면 거절된다.
        mvc.perform(
                        put(pollPath() + "/responses/me")
                                .with(csrf())
                                .cookie(late.cookie())
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(json.writeValueAsString(
                                        Map.of(
                                                "optionId",
                                                understoodOptionId
                                        )
                                ))
                )
                .andExpect(status().isConflict());

        assertThat(responseCount())
                .isEqualTo(2);

        assertSummary(results(), 5, 2, 3);
    }

    @Test
    void 응답한_청중이_재입장해도_기존_선택과_참여자_수가_유지된다()
            throws Exception {

        // 1. 청중 입장
        Participant participant = join("청중");

        // 2. 이해도 체크 시작 및 응답
        createUnderstandingPoll();

        respond(participant, understoodOptionId);

        assertSummary(results(), 1, 1, 0);

        assertThat(responseCount())
                .isEqualTo(1);

        // 3. 기존 쿠키를 가지고 강연방에 재입장한다.
        MvcResult rejoined = mvc.perform(
                        post(
                                "/api/v1/public/rooms/{code}/participants",
                                joinCode
                        )
                                .with(csrf())
                                .cookie(participant.cookie())
                                .header(
                                        "Idempotency-Key",
                                        UUID.randomUUID().toString()
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(json.writeValueAsString(
                                        Map.of(
                                                "nickname",
                                                "재입장한 청중"
                                        )
                                ))
                )
                .andExpect(status().isOk())
                .andReturn();

        JsonNode rejoinedData = data(rejoined);

        assertThat(
                rejoinedData.path("participantId").asLong()
        ).isEqualTo(participant.id());

        assertThat(participantCount())
                .isEqualTo(1);

        // 4. 재입장 이후 기존 응답을 다시 조회한다.
        JsonNode restored = data(
                mvc.perform(
                                get(pollPath() + "/responses/me")
                                        .cookie(participant.cookie())
                        )
                        .andExpect(status().isOk())
                        .andReturn()
        );

        assertThat(restored.path("participantId").asLong())
                .isEqualTo(participant.id());

        assertThat(restored.path("optionId").asLong())
                .isEqualTo(understoodOptionId);

        // 5. 재입장한 청중이 기존 선택을 변경한다.
        respond(participant, confusedOptionId);

        JsonNode changed = results();

        assertSummary(changed, 1, 1, 0);

        assertThat(optionCount(
                changed, understoodOptionId))
                .isZero();

        assertThat(optionCount(
                changed, confusedOptionId))
                .isEqualTo(1);

        // 새로운 참여자나 새로운 응답 행이
        // 추가되어서는 안 된다.
        assertThat(participantCount())
                .isEqualTo(1);

        assertThat(responseCount())
                .isEqualTo(1);

        // 6. 마감 후에도 최종 선택이 유지된다.
        closePoll();

        JsonNode closed = results();

        assertThat(closed.path("status").asText())
                .isEqualTo("CLOSED");

        assertSummary(closed, 1, 1, 0);

        assertThat(optionCount(
                closed, confusedOptionId))
                .isEqualTo(1);
    }

    private void createUnderstandingPoll()
            throws Exception {

        MvcResult result = mvc.perform(
                        post(
                                "/api/v1/rooms/{roomId}/polls",
                                roomId
                        )
                                .with(authentication(host()))
                                .with(csrf())
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                          "type": "UNDERSTANDING",
                                          "durationSeconds": 60
                                        }
                                        """)
                )
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode poll = data(result);

        pollId = poll.path("pollId").asLong();

        assertThat(pollId).isPositive();

        for (JsonNode option : poll.path("options")) {

            String understandingStatus =
                    option.path("understandingStatus")
                            .asText();

            if ("UNDERSTOOD".equals(understandingStatus)) {
                understoodOptionId =
                        option.path("optionId").asLong();
            }

            if ("CONFUSED".equals(understandingStatus)) {
                confusedOptionId =
                        option.path("optionId").asLong();
            }
        }

        assertThat(understoodOptionId).isPositive();
        assertThat(confusedOptionId).isPositive();
    }

    private Participant join(String nickname)
            throws Exception {

        MvcResult result = mvc.perform(
                        post(
                                "/api/v1/public/rooms/{code}/participants",
                                joinCode
                        )
                                .with(csrf())
                                .header(
                                        "Idempotency-Key",
                                        UUID.randomUUID().toString()
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(json.writeValueAsString(
                                        Map.of("nickname", nickname)
                                ))
                )
                .andExpect(status().isCreated())
                .andReturn();

        long participantId =
                data(result)
                        .path("participantId")
                        .asLong();

        assertThat(participantId).isPositive();

        String cookieName =
                "PARTICIPANT_SESSION_" + roomId;

        String cookieHeader =
                result.getResponse()
                        .getHeaders(HttpHeaders.SET_COOKIE)
                        .stream()
                        .filter(value ->
                                value.startsWith(cookieName + "="))
                        .findFirst()
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "참여자 쿠키가 없습니다."
                                ));

        String cookieValue = cookieHeader
                .split(";", 2)[0]
                .substring(cookieName.length() + 1);

        assertThat(cookieValue).isNotBlank();

        return new Participant(
                participantId,
                new Cookie(cookieName, cookieValue)
        );
    }

    private void respond(
            Participant participant,
            long optionId
    ) throws Exception {

        mvc.perform(
                        put(pollPath() + "/responses/me")
                                .with(csrf())
                                .cookie(participant.cookie())
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(json.writeValueAsString(
                                        Map.of("optionId", optionId)
                                ))
                )
                .andExpect(status().isOk());
    }

    private void closePoll() throws Exception {
        mvc.perform(
                        post(pollPath() + "/close")
                                .with(authentication(host()))
                                .with(csrf())
                )
                .andExpect(status().isOk());
    }

    private JsonNode results() throws Exception {
        return data(
                mvc.perform(
                                get(pollPath() + "/results")
                                        .with(authentication(host()))
                        )
                        .andExpect(status().isOk())
                        .andReturn()
        );
    }

    private void assertSummary(
            JsonNode result,
            long total,
            long responded,
            long unresponded
    ) {
        assertThat(
                result.path("totalParticipants").asLong(-1)
        ).isEqualTo(total);

        assertThat(
                result.path("respondedParticipants").asLong(-1)
        ).isEqualTo(responded);

        assertThat(
                result.path("unrespondedParticipants").asLong(-1)
        ).isEqualTo(unresponded);

        assertThat(total)
                .isEqualTo(responded + unresponded);

        assertThat(result.path("options").isArray())
                .isTrue();

        long sum = 0;

        for (JsonNode option : result.path("options")) {
            sum += option.path("count").asLong();
        }

        assertThat(sum).isEqualTo(responded);
    }

    private long optionCount(
            JsonNode result,
            long optionId
    ) {
        for (JsonNode option : result.path("options")) {

            if (option.path("optionId").asLong() == optionId) {
                return option.path("count").asLong(-1);
            }
        }

        throw new IllegalArgumentException(
                "선택지가 존재하지 않습니다: " + optionId
        );
    }

    private long participantCount() {
        return jdbc.sql("""
                SELECT COUNT(*)
                FROM lecture_participants
                WHERE lecture_room_id = ?
                """)
                .param(roomId)
                .query(Long.class)
                .single();
    }

    private long responseCount() {
        return jdbc.sql("""
                SELECT COUNT(*)
                FROM poll_responses
                WHERE poll_id = ?
                """)
                .param(pollId)
                .query(Long.class)
                .single();
    }

    private String pollPath() {
        return "/api/v1/rooms/"
                + roomId
                + "/polls/"
                + pollId;
    }

    private JsonNode data(MvcResult result)
            throws Exception {

        return json.readTree(
                        result.getResponse()
                                .getContentAsString()
                )
                .path("data");
    }

    private UsernamePasswordAuthenticationToken host() {
        return UsernamePasswordAuthenticationToken
                .authenticated(
                        hostEmail,
                        null,
                        List.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_HOST"
                                )
                        )
                );
    }

    private long insert(String sql, Object... params) {
        GeneratedKeyHolder keys =
                new GeneratedKeyHolder();

        jdbc.sql(sql)
                .params(params)
                .update(keys);

        return keys.getKey().longValue();
    }
}
