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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 이해도 체크 라운드 비교(POLL-06)는 강연방 소유자만 보고, 마감된 이해도 체크만 시간순으로 담는다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PollUnderstandingRoundsApiTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired PollService service;
    private String hostEmail;
    private long room;
    private final List<Long> participants = new ArrayList<>();

    @BeforeEach
    void prepare() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        hostEmail = UUID.randomUUID() + "@example.com";
        room = newRoom(hostEmail);
        for (int i = 1; i <= 6; i++) {
            participants.add(newParticipant(room, "청중" + i));
        }
    }

    @Test
    void 마감된_이해도_체크만_만든_순서대로_담고_진행_중인_라운드와_일반_투표는_뺀다() throws Exception {
        PollView first = closedRound(room, 3, 1, 1);
        service.close(room, service.create(room, hostEmail, new CreatePollRequest(
                PollType.GENERAL, "일반 투표", List.of("가", "나"), 60)).pollId(), hostEmail);
        PollView second = closedRound(room, 2, 2, 2);
        service.create(room, hostEmail, new CreatePollRequest(PollType.UNDERSTANDING, null, null, 60));

        JsonNode data = fetch(room, "");

        assertThat(data.path("roomId").asLong()).isEqualTo(room);
        assertThat(data.path("ratio").asDouble()).isEqualTo(40.0);
        assertThat(data.path("minCount").asInt()).isEqualTo(3);
        assertThat(data.path("rounds")).hasSize(2);
        assertThat(data.path("rounds").get(0).path("pollId").asLong()).isEqualTo(first.pollId());
        assertThat(data.path("rounds").get(1).path("pollId").asLong()).isEqualTo(second.pollId());
    }

    @Test
    void 선택지별_인원과_이해_부족_수치를_계산한다() throws Exception {
        closedRound(room, 2, 2, 2);

        JsonNode round = fetch(room, "").path("rounds").get(0);

        assertThat(round.path("totalParticipants").asLong()).isEqualTo(6);
        assertThat(round.path("respondedParticipants").asLong()).isEqualTo(6);
        assertThat(round.path("counts").path("UNDERSTOOD").asLong()).isEqualTo(2);
        assertThat(round.path("counts").path("CONFUSED").asLong()).isEqualTo(2);
        assertThat(round.path("counts").path("NOT_UNDERSTOOD").asLong()).isEqualTo(2);
        assertThat(round.path("lackCount").asLong()).isEqualTo(4);
        assertThat(round.path("lackRatio").asDouble()).isEqualTo(66.7);
        assertThat(round.path("lowUnderstanding").asBoolean()).isTrue();
        assertThat(round.path("openedAt").asText()).isNotBlank();
        assertThat(round.path("closedAt").asText()).isNotBlank();
    }

    @Test
    void 이해_부족_기준은_명수와_비율을_모두_충족해야_하고_경계값을_포함한다() throws Exception {
        // 응답 5명 중 헷갈림·모르겠음 2명 = 40.0%. 기본 기준(3명 이상)에는 명수가 모자란다.
        closedRound(room, 3, 1, 1);

        assertThat(fetch(room, "").path("rounds").get(0).path("lowUnderstanding").asBoolean()).isFalse();
        assertThat(fetch(room, "?minCount=2").path("rounds").get(0).path("lowUnderstanding").asBoolean()).isTrue();
        assertThat(fetch(room, "?minCount=2&ratio=40").path("rounds").get(0).path("lowUnderstanding").asBoolean()).isTrue();
        assertThat(fetch(room, "?minCount=2&ratio=40.1").path("rounds").get(0).path("lowUnderstanding").asBoolean()).isFalse();
        JsonNode custom = fetch(room, "?minCount=2&ratio=50");
        assertThat(custom.path("ratio").asDouble()).isEqualTo(50.0);
        assertThat(custom.path("minCount").asInt()).isEqualTo(2);
        assertThat(custom.path("rounds").get(0).path("lowUnderstanding").asBoolean()).isFalse();
    }

    @Test
    void 응답이_없는_라운드는_부족_비율이_0이고_미응답을_모르겠음으로_세지_않는다() throws Exception {
        closedRound(room, 0, 0, 0);

        JsonNode round = fetch(room, "").path("rounds").get(0);

        assertThat(round.path("respondedParticipants").asLong()).isZero();
        assertThat(round.path("counts").path("NOT_UNDERSTOOD").asLong()).isZero();
        assertThat(round.path("lackCount").asLong()).isZero();
        assertThat(round.path("lackRatio").asDouble()).isEqualTo(0.0);
        assertThat(round.path("lowUnderstanding").asBoolean()).isFalse();
    }

    @Test
    void 마감된_라운드가_없으면_빈_목록이다() throws Exception {
        service.create(room, hostEmail, new CreatePollRequest(PollType.UNDERSTANDING, null, null, 60));

        assertThat(fetch(room, "").path("rounds")).isEmpty();
    }

    @Test
    void 다른_강연방의_라운드는_섞이지_않는다() throws Exception {
        String otherEmail = UUID.randomUUID() + "@example.com";
        long otherRoom = newRoom(otherEmail);
        long otherParticipant = newParticipant(otherRoom, "다른 방 청중");
        PollView mine = closedRound(room, 1, 0, 0);
        PollView other = service.create(otherRoom, otherEmail, new CreatePollRequest(PollType.UNDERSTANDING, null, null, 60));
        service.respond(otherRoom, other.pollId(), otherParticipant, other.options().get(2).optionId());
        service.close(otherRoom, other.pollId(), otherEmail);

        JsonNode mineData = fetch(room, "");
        JsonNode otherData = json.readTree(mvc.perform(get(url(otherRoom, "")).with(authentication(host(otherEmail))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");

        assertThat(mineData.path("rounds")).hasSize(1);
        assertThat(mineData.path("rounds").get(0).path("pollId").asLong()).isEqualTo(mine.pollId());
        assertThat(otherData.path("rounds")).hasSize(1);
        assertThat(otherData.path("rounds").get(0).path("pollId").asLong()).isEqualTo(other.pollId());
        assertThat(otherData.path("rounds").get(0).path("counts").path("NOT_UNDERSTOOD").asLong()).isEqualTo(1);
    }

    @Test
    void 청중_개개인의_정보는_내려주지_않는다() throws Exception {
        closedRound(room, 1, 1, 1);

        String body = mvc.perform(get(url(room, "")).with(authentication(host(hostEmail))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("participantId").doesNotContain("nickname").doesNotContain("청중");
    }

    @Test
    void 로그인하지_않으면_401이다() throws Exception {
        mvc.perform(get(url(room, ""))).andExpect(status().isUnauthorized());
    }

    @Test
    void 청중_역할은_조회할_수_없다() throws Exception {
        var participantRole = UsernamePasswordAuthenticationToken.authenticated(
                "audience", null, List.of(new SimpleGrantedAuthority("ROLE_PARTICIPANT")));

        mvc.perform(get(url(room, "")).with(authentication(participantRole))).andExpect(status().isForbidden());
    }

    @Test
    void 다른_강연자는_내_방의_라운드를_조회할_수_없다() throws Exception {
        String stranger = UUID.randomUUID() + "@example.com";
        insert("INSERT INTO users(email,password_hash,name,created_at) VALUES (?, 'hash', '다른 강연자', ?)",
                stranger, LocalDateTime.now(SEOUL));

        mvc.perform(get(url(room, "")).with(authentication(host(stranger)))).andExpect(status().isForbidden());
    }

    @Test
    void 없는_강연방은_404이다() throws Exception {
        mvc.perform(get(url(Long.MAX_VALUE, "")).with(authentication(host(hostEmail)))).andExpect(status().isNotFound());
    }

    @Test
    void 기준_값이_범위를_벗어나면_400이다() throws Exception {
        for (String query : new String[]{"?minCount=0", "?minCount=-1", "?ratio=0", "?ratio=-5", "?ratio=100.1",
                "?minCount=abc", "?ratio=abc"}) {
            mvc.perform(get(url(room, query)).with(authentication(host(hostEmail))))
                    .andExpect(status().isBadRequest());
        }
        fetch(room, "?minCount=1&ratio=100");
    }

    private JsonNode fetch(long roomId, String query) throws Exception {
        String body = mvc.perform(get(url(roomId, query)).with(authentication(host(hostEmail))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }

    /** 이해도 체크를 열고 이해됨·헷갈림·모르겠음 순으로 청중이 응답한 뒤 마감한다. 청중은 한 라운드에 한 번씩만 쓴다. */
    private PollView closedRound(long roomId, int understood, int confused, int notUnderstood) {
        PollView poll = service.create(roomId, hostEmail, new CreatePollRequest(PollType.UNDERSTANDING, null, null, 60));
        int next = 0;
        int[] counts = {understood, confused, notUnderstood};
        for (int option = 0; option < counts.length; option++) {
            for (int i = 0; i < counts[option]; i++) {
                service.respond(roomId, poll.pollId(), participants.get(next++), poll.options().get(option).optionId());
            }
        }
        service.close(roomId, poll.pollId(), hostEmail);
        return poll;
    }

    private static String url(long roomId, String query) {
        return "/api/v1/rooms/" + roomId + "/polls/understanding-rounds" + query;
    }

    private static UsernamePasswordAuthenticationToken host(String email) {
        return UsernamePasswordAuthenticationToken.authenticated(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_HOST")));
    }

    private long newRoom(String email) {
        long host = insert("INSERT INTO users(email,password_hash,name,created_at) VALUES (?, 'hash', '강연자', ?)",
                email, LocalDateTime.now(SEOUL));
        return insert("""
                INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at)
                VALUES (?, 'test', ?, 'LIVE', ?)
                """, host, UUID.randomUUID().toString(), LocalDateTime.now(SEOUL));
    }

    private long newParticipant(long roomId, String nickname) {
        return insert("""
                INSERT INTO lecture_participants(lecture_room_id,nickname,participant_token_hash,joined_at)
                VALUES (?, ?, ?, ?)
                """, roomId, nickname, UUID.randomUUID().toString(), LocalDateTime.now(SEOUL));
    }

    private long insert(String sql, Object... params) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql(sql).params(params).update(keys);
        return keys.getKey().longValue();
    }
}
