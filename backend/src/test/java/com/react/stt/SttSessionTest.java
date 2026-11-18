package com.react.stt;

import com.react.stt.service.SttSessionService;
import com.react.stt.service.SttSessionService.StartResult;
import com.react.stt.service.SttSessionService.StopReason;
import com.react.stt.dto.SttSegmentRequest;
import com.react.stt.dto.SttGap;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    void startReplayStopAndEndedRoomConflict() throws Exception {
        var key = UUID.randomUUID();
        mvc.perform(start(key).with(user(email).roles("HOST"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.canRecord").value(true));
        mvc.perform(start(key).with(user(email).roles("HOST"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").value(room))
                .andExpect(jsonPath("$.data.sttSessionId").isNumber());
        long id = service.start(room, email, key).session().sttSessionId();
        mvc.perform(post("/api/v1/rooms/{room}/stt/sessions/{id}/stop", room, id)
                        .with(user(email).roles("HOST")).contentType("application/json")
                        .content("{\"reason\":\"MANUAL_STOP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("STOPPED"))
                .andExpect(jsonPath("$.data.canRecord").value(false));
        jdbc.sql("UPDATE lecture_rooms SET status='ENDED' WHERE id=?").param(room).update();
        mvc.perform(start(UUID.randomUUID()).with(user(email).roles("HOST"))).andExpect(status().isConflict());
    }

    @Test
    void endedRoomDisablesRecordingOnStartReplayBeforeSessionStops() throws Exception {
        UUID key = UUID.randomUUID();
        var session = service.start(room, email, key).session();
        assertThat(session.canRecord()).isTrue();
        mvc.perform(start(key).with(user(email).roles("HOST")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.canRecord").value(true));

        jdbc.sql("UPDATE lecture_rooms SET status='ENDED', ended_at=NOW() WHERE id=?").param(room).update();
        mvc.perform(start(key).with(user(email).roles("HOST"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sttSessionId").value(session.sttSessionId()))
                .andExpect(jsonPath("$.data.startedAt").value(session.startedAt().toString()))
                .andExpect(jsonPath("$.data.status").value("RECORDING"))
                .andExpect(jsonPath("$.data.endedAt").isEmpty())
                .andExpect(jsonPath("$.data.canRecord").value(false));
        mvc.perform(start(UUID.randomUUID()).with(user(email).roles("HOST"))).andExpect(status().isConflict());
        var line = new SttSegmentRequest(key, "after-end", "발언", session.startedAt(), session.startedAt());
        mvc.perform(segment(room, session.sttSessionId(), line).with(user(email).roles("HOST")))
                .andExpect(status().isConflict());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM stt_sessions WHERE lecture_room_id=?")
                .param(room).query(Long.class).single()).isEqualTo(1);

        service.stop(room, session.sttSessionId(), email, StopReason.MANUAL_STOP);
        mvc.perform(start(key).with(user(email).roles("HOST"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("STOPPED"))
                .andExpect(jsonPath("$.data.canRecord").value(false));
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
        var otherTab = new SttSegmentRequest(UUID.randomUUID(), "seg-1", "첫 발언", time, time);
        mvc.perform(segment(room, id, otherTab).with(user(email).roles("HOST"))).andExpect(status().isForbidden());
        String otherCode = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at)
                SELECT host_user_id,'other',?,'LIVE',NOW() FROM lecture_rooms WHERE id=?
                """).params(otherCode, room).update();
        long otherRoom = jdbc.sql("SELECT id FROM lecture_rooms WHERE join_code=?").param(otherCode)
                .query(Long.class).single();
        mvc.perform(segment(otherRoom, id, line).with(user(email).roles("HOST"))).andExpect(status().isNotFound());
        var blank = new SttSegmentRequest(key, "seg-1", "  ", time, time);
        mvc.perform(segment(room, id, blank).with(user(email).roles("HOST"))).andExpect(status().isBadRequest());
        var reversed = new SttSegmentRequest(key, "seg-1", "첫 발언", time, time.minusMillis(1));
        mvc.perform(segment(room, id, reversed).with(user(email).roles("HOST"))).andExpect(status().isBadRequest());

        var later = new SttSegmentRequest(key, "seg-2", "다음 발언", time.plusSeconds(1), time.plusSeconds(2));
        mvc.perform(segment(room, id, later).with(user(email).roles("HOST"))).andExpect(status().isCreated());
        String original = mvc.perform(segment(room, id, line).with(user(email).roles("HOST"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.segmentStartAt").value(time.toString()))
                .andExpect(jsonPath("$.data.segmentEndAt").value(time.plusMillis(1).toString()))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        service.stop(room, id, email, StopReason.MANUAL_STOP);
        var late = new SttSegmentRequest(key, "seg-3", "늦은 발언", time, time);
        mvc.perform(segment(room, id, late).with(user(email).roles("HOST"))).andExpect(status().isConflict());
        jdbc.sql("UPDATE lecture_rooms SET status='ENDED' WHERE id=?").param(room).update();
        mvc.perform(segment(room, id, line).with(user(email).roles("HOST"))
                        .content(json.writeValueAsString(line).replace("01:00:12.350Z", "10:00:12.350+09:00")))
                .andExpect(status().isOk()).andExpect(content().json(original));
        var changed = new SttSegmentRequest(key, "seg-1", "첫 발언", time, time.plusMillis(2));
        mvc.perform(segment(room, id, changed).with(user(email).roles("HOST"))).andExpect(status().isConflict());
        var changedText = new SttSegmentRequest(key, "seg-1", "수정 발언", time, time.plusMillis(1));
        mvc.perform(segment(room, id, changedText).with(user(email).roles("HOST"))).andExpect(status().isConflict());
        mvc.perform(segment(room, id, late).with(user(email).roles("HOST"))).andExpect(status().isConflict());
    }

    @Test
    void sameSessionTimesAcrossUtcAndSeoulConnections() {
        UUID key = UUID.randomUUID();
        try (var utc = connectionInZone("+00:00"); var seoul = connectionInZone("+09:00")) {
            var utcJdbc = JdbcClient.create(utc);
            var utcService = new SttSessionService(utcJdbc);
            var seoulService = new SttSessionService(JdbcClient.create(seoul));
            var started = utcService.start(room, email, key).session();
            long id = started.sttSessionId();
            assertThat(started.endedAt()).isNull();
            assertThat(seoulService.start(room, email, key).session()).isEqualTo(started);

            var stopped = utcService.stop(room, id, email, StopReason.MANUAL_STOP);
            assertThat(stopped.endedAt()).isNotNull();
            assertThat(seoulService.stop(room, id, email, StopReason.ERROR)).isEqualTo(stopped);
            var offset = java.time.ZoneOffset.ofHours(9);
            assertThat(utcJdbc.sql("SELECT started_at FROM stt_sessions WHERE id=?").param(id)
                    .query(LocalDateTime.class).single()).isEqualTo(LocalDateTime.ofInstant(started.startedAt(), offset));
            assertThat(utcJdbc.sql("SELECT ended_at FROM stt_sessions WHERE id=?").param(id)
                    .query(LocalDateTime.class).single()).isEqualTo(LocalDateTime.ofInstant(stopped.endedAt(), offset));
        }
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

    private MockHttpServletRequestBuilder openGap(long session, UUID key, String event, Instant start) throws Exception {
        var body = new SttGap.OpenRequest(key, event, SttGap.Reason.ERROR, start);
        return post("/api/v1/rooms/{room}/stt/sessions/{id}/gaps", room, session)
                .contentType("application/json").content(json.writeValueAsBytes(body));
    }

    private MockHttpServletRequestBuilder closeGap(long session, long gap, UUID key, Instant end) throws Exception {
        return patch("/api/v1/rooms/{room}/stt/sessions/{id}/gaps/{gap}/close", room, session, gap)
                .with(user(email).roles("HOST")).contentType("application/json")
                .content(json.writeValueAsBytes(new SttGap.CloseRequest(key, end)));
    }

    @Test
    void gapsValidateOwnerKeyAndReplayAfterAutomaticClosure() throws Exception {
        UUID key = UUID.randomUUID();
        var session = service.start(room, email, key).session();
        long id = session.sttSessionId();
        Instant start = session.startedAt();
        mvc.perform(openGap(id, UUID.randomUUID(), "a", start).with(user(email).roles("HOST"))).andExpect(status().isForbidden());
        mvc.perform(openGap(id, key, "a", start.minusSeconds(1)).with(user(email).roles("HOST"))).andExpect(status().isBadRequest());
        mvc.perform(openGap(id, key, "a", start.plusSeconds(3600)).with(user(email).roles("HOST"))).andExpect(status().isBadRequest());
        var response = mvc.perform(openGap(id, key, "a", start.plusMillis(999)).with(user(email).roles("HOST")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.startedAt").value(start.toString()))
                .andReturn().getResponse();
        long gap = json.readTree(response.getContentAsString()).at("/data/gapId").asLong();
        mvc.perform(openGap(id, key, "b", start).with(user(email).roles("HOST"))).andExpect(status().isConflict());
        mvc.perform(closeGap(id, gap, UUID.randomUUID(), start)).andExpect(status().isForbidden());
        var stopped = service.stop(room, id, email, StopReason.MANUAL_STOP);
        mvc.perform(openGap(id, key, "a", start).with(user(email).roles("HOST"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.endedAt").value(stopped.endedAt().toString()))
                .andExpect(jsonPath("$.data.reason").value("ERROR"));
        mvc.perform(closeGap(id, gap, key, stopped.endedAt().plusMillis(999))).andExpect(status().isOk());
        mvc.perform(closeGap(id, gap, key, stopped.endedAt().minusSeconds(1))).andExpect(status().isConflict());
        mvc.perform(openGap(id, key, "b", start).with(user(email).roles("HOST"))).andExpect(status().isConflict());
        var another = service.start(room, email, UUID.randomUUID()).session();
        service.stop(room, another.sttSessionId(), email, StopReason.MANUAL_STOP);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM stt_gaps WHERE stt_session_id=?")
                .param(another.sttSessionId()).query(Long.class).single()).isZero();
    }

    @Test
    void adjacentGapsAndDelayedRecoveryAfterLectureEnds() throws Exception {
        UUID key = UUID.randomUUID();
        long id = service.start(room, email, key).session().sttSessionId();
        Instant start = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).minusSeconds(60);
        LocalDateTime localStart = LocalDateTime.ofInstant(start, java.time.ZoneOffset.ofHours(9));
        jdbc.sql("UPDATE stt_sessions SET started_at=? WHERE id=?").params(localStart, id).update();
        var first = service.openGap(room, id, email, new SttGap.OpenRequest(key, "first", SttGap.Reason.ERROR, start)).gap();
        Instant boundary = start.plusSeconds(5);
        mvc.perform(closeGap(id, first.gapId(), key, start.minusSeconds(1))).andExpect(status().isBadRequest());
        mvc.perform(closeGap(id, first.gapId(), key, start.plusSeconds(3600))).andExpect(status().isBadRequest());
        mvc.perform(closeGap(id, first.gapId(), key, boundary)).andExpect(status().isOk());
        mvc.perform(openGap(id, key, "next", start).with(user(email).roles("HOST"))).andExpect(status().isConflict());
        var next = service.openGap(room, id, email,
                new SttGap.OpenRequest(key, "next", SttGap.Reason.PERMISSION_DENIED, boundary)).gap();
        jdbc.sql("UPDATE lecture_rooms SET status='ENDED', ended_at=? WHERE id=?")
                .params(localStart.plusSeconds(12), room).update();
        mvc.perform(closeGap(id, next.gapId(), key, start.plusSeconds(13))).andExpect(status().isBadRequest());
        mvc.perform(closeGap(id, next.gapId(), key, start.plusSeconds(12))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reason").value("PERMISSION_DENIED"));
        mvc.perform(openGap(id, key, "late", start.plusSeconds(12)).with(user(email).roles("HOST"))).andExpect(status().isConflict());
        mvc.perform(openGap(id, key, "first", start).with(user(email).roles("HOST"))).andExpect(status().isOk());
    }

    @Test
    void concurrentGapStartsLeaveOneOpenGap() throws Exception {
        UUID key = UUID.randomUUID();
        var session = service.start(room, email, key).session();
        var gate = new CyclicBarrier(2);
        Callable<Integer> send = () -> {
            var request = openGap(session.sttSessionId(), key, UUID.randomUUID().toString(), session.startedAt());
            gate.await(5, TimeUnit.SECONDS);
            return mvc.perform(request.with(user(email).roles("HOST"))).andReturn().getResponse().getStatus();
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(send);
            var second = pool.submit(send);
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM stt_gaps WHERE stt_session_id=? AND ended_at IS NULL")
                .param(session.sttSessionId()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void recordQueryIncludesOverlappingRecordsAcrossSessionsAndKeepsOriginalTimes() throws Exception {
        UUID key = UUID.randomUUID();
        long first = service.start(room, email, key).session().sttSessionId();
        Instant from = Instant.parse("2026-11-17T01:01:00Z");
        Instant to = from.plusSeconds(60);
        service.saveSegment(room, first, email, new SttSegmentRequest(key, "ends-at-from", "excluded",
                from.minusSeconds(2), from));
        service.saveSegment(room, first, email, new SttSegmentRequest(key, "overlap", "whole transcript",
                from.minusSeconds(2), from.plusSeconds(4)));
        service.stop(room, first, email, StopReason.MANUAL_STOP);
        UUID nextKey = UUID.randomUUID();
        long second = service.start(room, email, nextKey).session().sttSessionId();
        service.saveSegment(room, second, email, new SttSegmentRequest(nextKey, "point", "point",
                from, from));
        service.saveSegment(room, second, email, new SttSegmentRequest(nextKey, "tie", "tie",
                from, from.plusSeconds(1)));
        service.saveSegment(room, second, email, new SttSegmentRequest(nextKey, "starts-at-to", "excluded",
                to, to.plusSeconds(1)));
        LocalDateTime localFrom = LocalDateTime.ofInstant(from, java.time.ZoneOffset.ofHours(9));
        jdbc.sql("""
                INSERT INTO stt_gaps(stt_session_id,event_key,reason,started_at,ended_at)
                VALUES (?, 'point','ERROR',?,?), (?, 'closed','ERROR',?,?), (?, 'open','ERROR',?,NULL)
                """).params(first, localFrom, localFrom, first, localFrom.minusSeconds(10), localFrom,
                        second, localFrom.plusSeconds(30)).update();

        mvc.perform(get("/api/v1/rooms/{room}/stt/segments", room).with(user(email).roles("HOST"))
                        .param("from", from.toString()).param("to", to.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.segments.length()").value(3))
                .andExpect(jsonPath("$.data.segments[0].transcript").value("whole transcript"))
                .andExpect(jsonPath("$.data.segments[0].sessionId").value(first))
                .andExpect(jsonPath("$.data.segments[0].segmentStartAt").value(from.minusSeconds(2).toString()))
                .andExpect(jsonPath("$.data.segments[1].transcript").value("point"))
                .andExpect(jsonPath("$.data.segments[2].transcript").value("tie"))
                .andExpect(jsonPath("$.data.gaps.length()").value(2))
                .andExpect(jsonPath("$.data.gaps[1].endedAt").isEmpty());
        assertThat(service.records(room, email, null, null).segments()).hasSize(5);
        assertThat(service.records(room, email, null, from).segments()).hasSize(2);
        assertThat(service.records(room, email, to, null).segments()).hasSize(1);
        assertThat(service.records(room, email, Instant.now().plusSeconds(60), null).gaps()).isEmpty();
        assertThat(service.records(room, email, from.plusMillis(1), to).gaps()).hasSize(1);
    }

    @Test
    void recordQueryValidatesAccessAndRangeAndAllowsEveryRoomStatus() throws Exception {
        String url = "/api/v1/rooms/{room}/stt/segments";
        mvc.perform(get(url, Long.MAX_VALUE).with(user(email).roles("HOST"))).andExpect(status().isNotFound());
        mvc.perform(get(url, room).with(user(email).roles("HOST")).param("from", "invalid")).andExpect(status().isBadRequest());
        String time = "2026-11-17T01:01:00Z";
        mvc.perform(get(url, room).with(user(email).roles("HOST")).param("from", time).param("to", time))
                .andExpect(status().isBadRequest());
        mvc.perform(get(url, room).with(user(email).roles("HOST")).param("from", "2026-11-17T01:02:00Z").param("to", time))
                .andExpect(status().isBadRequest());
        for (String state : java.util.List.of("READY", "LIVE", "ENDED")) {
            jdbc.sql("UPDATE lecture_rooms SET status=? WHERE id=?").params(state, room).update();
            mvc.perform(get(url, room).with(user(email).roles("HOST"))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.segments").isEmpty()).andExpect(jsonPath("$.data.gaps").isEmpty());
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
