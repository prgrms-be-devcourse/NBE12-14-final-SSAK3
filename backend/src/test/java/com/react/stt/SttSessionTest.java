package com.react.stt;

import com.react.stt.service.SttSessionService;
import com.react.stt.service.SttSessionService.StartResult;
import com.react.stt.service.SttSessionService.StopReason;
import com.react.stt.dto.SttSegmentRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SttSessionTest {
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired DataSourceProperties database;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SttSessionService service;
    private final String email = UUID.randomUUID() + "@example.com";
    private long room;

    @BeforeEach
    void prepareRoom() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc.sql("INSERT INTO users(email,password_hash,name,created_at) VALUES (?, 'hash','host', NOW())")
                .param(email).update();
        jdbc.sql("""
                INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at)
                SELECT id,'test',?,'LIVE',NOW() FROM users WHERE email=?
                """).params(email, email).update();
        room = jdbc.sql("SELECT id FROM lecture_rooms WHERE join_code=?").param(email).query(Long.class).single();
    }

    private MockHttpServletRequestBuilder start(UUID key) {
        return post("/api/v1/rooms/{id}/stt/sessions", room).contentType("application/json")
                .content("{\"startKey\":\"" + key + "\"}");
    }

    @Test
    void authenticatedLiveOwnerOnly() throws Exception {
        var key = UUID.randomUUID();
        mvc.perform(start(key)).andExpect(status().isUnauthorized());
        mvc.perform(start(key).with(user("other@example.com"))).andExpect(status().isForbidden());
        mvc.perform(start(key).with(user(email))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.canRecord").value(true));
        mvc.perform(start(key).with(user(email))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").value(room))
                .andExpect(jsonPath("$.data.sttSessionId").isNumber());
        long id = service.start(room, email, key).session().sttSessionId();
        mvc.perform(post("/api/v1/rooms/{room}/stt/sessions/{id}/stop", room, id)
                        .with(user(email)).contentType("application/json")
                        .content("{\"reason\":\"MANUAL_STOP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("STOPPED"))
                .andExpect(jsonPath("$.data.canRecord").value(false));
        jdbc.sql("UPDATE lecture_rooms SET status='ENDED' WHERE id=?").param(room).update();
        mvc.perform(start(UUID.randomUUID()).with(user(email))).andExpect(status().isConflict());
    }

    @Test
    void simultaneousStartsAndLateRequests() throws Exception {
        var gate = new CyclicBarrier(2);
        var firstKey = UUID.randomUUID();
        var secondKey = UUID.randomUUID();
        StartResult first;
        StartResult second;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(contender(gate, firstKey));
            var b = pool.submit(contender(gate, secondKey));
            first = a.get(10, TimeUnit.SECONDS);
            second = b.get(10, TimeUnit.SECONDS);
        }
        long id = first.session().sttSessionId();
        assertThat(second.session().sttSessionId()).isEqualTo(id);
        assertThat(first.created()).isNotEqualTo(second.created());
        assertThat(first.session().canRecord()).isEqualTo(first.created());
        assertThat(second.session().canRecord()).isEqualTo(second.created());
        var stopped = service.stop(room, id, email, StopReason.MANUAL_STOP);
        assertThat(stopped.endedAt()).isNotNull();
        var replacement = service.start(room, email, UUID.randomUUID());
        assertThat(replacement.created()).isTrue();
        assertThat(service.stop(room, id, email, StopReason.ERROR)).isEqualTo(stopped);
        var delayed = service.start(room, email, first.created() ? firstKey : secondKey);
        assertThat(delayed.session()).isEqualTo(stopped);
    }

    private Callable<StartResult> contender(CyclicBarrier gate, UUID key) {
        return () -> {
            gate.await(5, TimeUnit.SECONDS);
            return service.start(room, email, key);
        };
    }

    private MockHttpServletRequestBuilder segment(long roomId, long sessionId, SttSegmentRequest request) throws Exception {
        return post("/api/v1/rooms/{room}/stt/sessions/{session}/segments", roomId, sessionId)
                .contentType("application/json").content(json.writeValueAsBytes(request));
    }

    @Test
    void segmentAuthorizationReplayAndClosure() throws Exception {
        UUID key = UUID.randomUUID();
        long id = service.start(room, email, key).session().sttSessionId();
        Instant time = Instant.parse("2026-11-17T01:00:12.350Z");
        var line = new SttSegmentRequest(key, "seg-1", "첫 발언", time, time.plusMillis(1));
        mvc.perform(segment(room, id, line)).andExpect(status().isUnauthorized());
        mvc.perform(segment(room, id, line).with(user("other@example.com"))).andExpect(status().isForbidden());
        var otherTab = new SttSegmentRequest(UUID.randomUUID(), "seg-1", "첫 발언", time, time);
        mvc.perform(segment(room, id, otherTab).with(user(email))).andExpect(status().isForbidden());
        String otherCode = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at)
                SELECT host_user_id,'other',?,'LIVE',NOW() FROM lecture_rooms WHERE id=?
                """).params(otherCode, room).update();
        long otherRoom = jdbc.sql("SELECT id FROM lecture_rooms WHERE join_code=?").param(otherCode)
                .query(Long.class).single();
        mvc.perform(segment(otherRoom, id, line).with(user(email))).andExpect(status().isNotFound());
        var blank = new SttSegmentRequest(key, "seg-1", "  ", time, time);
        mvc.perform(segment(room, id, blank).with(user(email))).andExpect(status().isBadRequest());
        var reversed = new SttSegmentRequest(key, "seg-1", "첫 발언", time, time.minusMillis(1));
        mvc.perform(segment(room, id, reversed).with(user(email))).andExpect(status().isBadRequest());

        var later = new SttSegmentRequest(key, "seg-2", "다음 발언", time.plusSeconds(1), time.plusSeconds(2));
        mvc.perform(segment(room, id, later).with(user(email))).andExpect(status().isCreated());
        String original = mvc.perform(segment(room, id, line).with(user(email))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.segmentStartAt").value(time.toString()))
                .andExpect(jsonPath("$.data.segmentEndAt").value(time.plusMillis(1).toString()))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        service.stop(room, id, email, StopReason.MANUAL_STOP);
        var late = new SttSegmentRequest(key, "seg-3", "늦은 발언", time, time);
        mvc.perform(segment(room, id, late).with(user(email))).andExpect(status().isConflict());
        jdbc.sql("UPDATE lecture_rooms SET status='ENDED' WHERE id=?").param(room).update();
        mvc.perform(segment(room, id, line).with(user(email))
                        .content(json.writeValueAsString(line).replace("01:00:12.350Z", "10:00:12.350+09:00")))
                .andExpect(status().isOk()).andExpect(content().json(original));
        var changed = new SttSegmentRequest(key, "seg-1", "첫 발언", time, time.plusMillis(2));
        mvc.perform(segment(room, id, changed).with(user(email))).andExpect(status().isConflict());
        var changedText = new SttSegmentRequest(key, "seg-1", "수정 발언", time, time.plusMillis(1));
        mvc.perform(segment(room, id, changedText).with(user(email))).andExpect(status().isConflict());
        mvc.perform(segment(room, id, late).with(user(email))).andExpect(status().isConflict());
    }

    @Test
    void sameSegmentAcrossUtcAndSeoulConnections() {
        UUID key = UUID.randomUUID();
        long id = service.start(room, email, key).session().sttSessionId();
        var start = Instant.parse("2026-11-17T18:00:12.350Z");
        var line = new SttSegmentRequest(key, "tz-1", "발언", start, start.plusMillis(41));

        try (var writer = connectionInZone("+00:00"); var reader = connectionInZone("+09:00")) {
            var writeJdbc = JdbcClient.create(writer);
            var first = new SttSessionService(writeJdbc).saveSegment(room, id, email, line);
            var replay = new SttSessionService(JdbcClient.create(reader)).saveSegment(room, id, email, line);
            assertThat(first.created()).isTrue();
            assertThat(replay.created()).isFalse();
            assertThat(replay.segment()).isEqualTo(first.segment());
            assertThat(replay.segment().segmentStartAt()).isEqualTo(start);
            assertThat(replay.segment().segmentEndAt()).isEqualTo(start.plusMillis(41));
            assertThat(writeJdbc.sql("SELECT segment_start_at FROM stt_segments WHERE id=?")
                    .param(first.segment().segmentId()).query(LocalDateTime.class).single())
                    .isEqualTo(LocalDateTime.parse("2026-11-18T03:00:12.350"));
        }
    }

    private SingleConnectionDataSource connectionInZone(String offset) {
        String baseUrl = database.getUrl().split("\\?", 2)[0];
        String zone = offset.equals("+00:00") ? "UTC" : "Asia/Seoul";
        var connection = new SingleConnectionDataSource(baseUrl + "?connectionTimeZone=" + zone,
                database.getUsername(), database.getPassword(), true);
        JdbcClient.create(connection).sql("SET SESSION time_zone = ?").param(offset).update();
        return connection;
    }

    @Test
    void concurrentSegmentRetriesStoreOneRow() throws Exception {
        UUID key = UUID.randomUUID();
        long id = service.start(room, email, key).session().sttSessionId();
        Instant time = Instant.parse("2026-11-17T01:00:12.350Z");
        var line = new SttSegmentRequest(key, "same-segment", "발언", time, time);
        var gate = new CyclicBarrier(2);
        Callable<SttSessionService.SegmentResult> submit = () -> {
            gate.await(5, TimeUnit.SECONDS);
            return service.saveSegment(room, id, email, line);
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(submit);
            var b = pool.submit(submit);
            var first = a.get(10, TimeUnit.SECONDS);
            var second = b.get(10, TimeUnit.SECONDS);
            assertThat(first.created()).isNotEqualTo(second.created());
            assertThat(first.segment()).isEqualTo(second.segment());
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM stt_segments WHERE stt_session_id=?")
                .param(id).query(Long.class).single()).isEqualTo(1);
    }
}
