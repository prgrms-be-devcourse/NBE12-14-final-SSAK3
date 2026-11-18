package com.react.feed;

import com.react.feed.service.UnderstandingService;
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
import java.util.List;
import java.util.UUID;

import static com.react.feed.dto.UnderstandingStatus.CONFUSED;
import static com.react.feed.dto.UnderstandingStatus.NOT_UNDERSTOOD;
import static com.react.feed.dto.UnderstandingStatus.UNDERSTOOD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UnderstandingSummaryTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired UnderstandingService service;
    private String hostEmail;
    private long room;

    @BeforeEach
    void prepareRoom() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        hostEmail = UUID.randomUUID() + "@example.com";
        room = newRoom(newUser(hostEmail), "LIVE");
    }

    @Test
    void summaryCountsLatestStatusOnlyAndSeparatesUnresponded() throws Exception {
        long a = newParticipant(room, "A");
        long b = newParticipant(room, "B");
        long c = newParticipant(room, "C");
        newParticipant(room, "D");
        // A 는 여러 번 바꿔도 최신 상태(CONFUSED) 한 번만 집계된다.
        service.change(room, a, UNDERSTOOD);
        service.change(room, a, NOT_UNDERSTOOD);
        service.change(room, a, CONFUSED);
        service.change(room, b, UNDERSTOOD);
        service.change(room, c, UNDERSTOOD);

        mvc.perform(get(url(room)).with(authentication(host(hostEmail))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").value(room))
                .andExpect(jsonPath("$.data.totalParticipants").value(4))
                .andExpect(jsonPath("$.data.respondedParticipants").value(3))
                .andExpect(jsonPath("$.data.unrespondedParticipants").value(1))
                .andExpect(jsonPath("$.data.responseRate").value(75.0))
                .andExpect(jsonPath("$.data.counts.UNDERSTOOD").value(2))
                .andExpect(jsonPath("$.data.counts.CONFUSED").value(1))
                .andExpect(jsonPath("$.data.counts.NOT_UNDERSTOOD").value(0))
                .andExpect(jsonPath("$.data.percentagesOfAllParticipants.UNDERSTOOD").value(50.0))
                .andExpect(jsonPath("$.data.percentagesOfAllParticipants.CONFUSED").value(25.0))
                .andExpect(jsonPath("$.data.percentagesOfAllParticipants.NOT_UNDERSTOOD").value(0.0))
                .andExpect(jsonPath("$.data.calculatedAt").exists());
    }

    @Test
    void summaryRoundsToOneDecimalAndUsesAllParticipantsAsDenominator() throws Exception {
        List<Long> ids = List.of(newParticipant(room, "A"), newParticipant(room, "B"), newParticipant(room, "C"));
        service.change(room, ids.get(0), NOT_UNDERSTOOD);

        mvc.perform(get(url(room)).with(authentication(host(hostEmail))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.responseRate").value(33.3))
                .andExpect(jsonPath("$.data.percentagesOfAllParticipants.NOT_UNDERSTOOD").value(33.3));
    }

    @Test
    void emptyRoomReturnsZeros() throws Exception {
        mvc.perform(get(url(room)).with(authentication(host(hostEmail))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalParticipants").value(0))
                .andExpect(jsonPath("$.data.responseRate").value(0.0))
                .andExpect(jsonPath("$.data.counts.UNDERSTOOD").value(0));
    }

    @Test
    void summaryIsIsolatedPerRoom() throws Exception {
        long mine = newParticipant(room, "내 청중");
        service.change(room, mine, UNDERSTOOD);
        long otherRoom = newRoom(newUser(UUID.randomUUID() + "@example.com"), "LIVE");
        long other = newParticipant(otherRoom, "다른방 청중");
        service.change(otherRoom, other, CONFUSED);

        mvc.perform(get(url(room)).with(authentication(host(hostEmail))))
                .andExpect(jsonPath("$.data.totalParticipants").value(1))
                .andExpect(jsonPath("$.data.counts.UNDERSTOOD").value(1))
                .andExpect(jsonPath("$.data.counts.CONFUSED").value(0));
    }

    @Test
    void summaryStaysReadableAfterRoomEnds() throws Exception {
        long a = newParticipant(room, "A");
        service.change(room, a, UNDERSTOOD);
        jdbc.sql("UPDATE lecture_rooms SET status = 'ENDED' WHERE id = ?").param(room).update();

        mvc.perform(get(url(room)).with(authentication(host(hostEmail))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.counts.UNDERSTOOD").value(1));
    }

    @Test
    void onlyRoomOwnerCanReadSummary() throws Exception {
        mvc.perform(get(url(room))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get(url(room)).with(authentication(host("other-" + UUID.randomUUID() + "@example.com"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get(url(Long.MAX_VALUE)).with(authentication(host(hostEmail))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
    }

    @Test
    void nonHostRoleIsForbiddenEvenWithMatchingEmail() throws Exception {
        var participantRole = UsernamePasswordAuthenticationToken.authenticated(
                hostEmail, null, List.of(new SimpleGrantedAuthority("ROLE_PARTICIPANT")));
        var noRole = UsernamePasswordAuthenticationToken.authenticated(hostEmail, null, List.of());

        mvc.perform(get(url(room)).with(authentication(participantRole)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get(url(room)).with(authentication(noRole)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private static String url(long roomId) {
        return "/api/v1/rooms/" + roomId + "/understanding/summary";
    }

    private static UsernamePasswordAuthenticationToken host(String email) {
        return UsernamePasswordAuthenticationToken.authenticated(email, null, List.of(new SimpleGrantedAuthority("ROLE_HOST")));
    }

    private long newUser(String email) {
        return insert("INSERT INTO users(email,password_hash,name,created_at) VALUES (?, 'hash', '강연자', NOW())", email);
    }

    private long newRoom(long hostUserId, String status) {
        return insert("""
                INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at)
                VALUES (?, 'test', ?, ?, NOW())
                """, hostUserId, UUID.randomUUID().toString(), status);
    }

    private long newParticipant(long roomId, String nickname) {
        return insert("""
                INSERT INTO lecture_participants(lecture_room_id,nickname,participant_token_hash,joined_at)
                VALUES (?, ?, ?, NOW())
                """, roomId, nickname, UUID.randomUUID().toString());
    }

    private long insert(String sql, Object... params) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql(sql).params(params).update(keys);
        return keys.getKey().longValue();
    }
}
