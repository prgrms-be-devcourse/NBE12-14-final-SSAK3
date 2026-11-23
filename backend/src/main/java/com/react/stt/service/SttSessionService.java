package com.react.stt.service;

import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.stt.dto.SttSessionView;
import com.react.stt.dto.SttGap;
import com.react.stt.dto.SttRecordsView;
import com.react.stt.dto.SttSegmentRequest;
import com.react.stt.dto.SttSegmentView;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.HashMap;

@Service
@Transactional
@RequiredArgsConstructor
public class SttSessionService {
    // STT DATETIME values use the original deployment's fixed +09:00 clock.
    private static final ZoneOffset STT_STORAGE_OFFSET = ZoneOffset.ofHours(9);
    private final JdbcClient jdbc;

    public enum StopReason {
        MANUAL_STOP("STOPPED"), ERROR("ERROR");

        private final String status;

        StopReason(String status) {
            this.status = status;
        }
    }
    public record StartResult(boolean created, SttSessionView session) {}
    public record SegmentResult(boolean created, SttSegmentView segment) {}
    public record GapResult(boolean created, SttGap.View gap) {}
    private record Room(String status, String email, LocalDateTime endedAt) {}
    private record Gap(long id, long sttSessionId, String eventKey, SttGap.Reason reason,
                       LocalDateTime startedAt, LocalDateTime endedAt) {
        SttGap.View view() {
            return new SttGap.View(id, sttSessionId, eventKey, reason, startedAt.toInstant(STT_STORAGE_OFFSET),
                    endedAt == null ? null : endedAt.toInstant(STT_STORAGE_OFFSET));
        }
    }
    private record Session(long id, long roomId, String status, LocalDateTime startedAt, LocalDateTime endedAt, String startKey) {
        SttSessionView view(String key, String roomStatus) {
            return new SttSessionView(id, roomId, status, startedAt.toInstant(STT_STORAGE_OFFSET),
                    endedAt == null ? null : endedAt.toInstant(STT_STORAGE_OFFSET),
                    key != null && key.equals(startKey) && "RECORDING".equals(status) && "LIVE".equals(roomStatus));
        }
    }

    private static final String SESSIONS = """
            SELECT id, lecture_room_id AS room_id, status, started_at, ended_at, start_key FROM stt_sessions WHERE lecture_room_id = ?
            """;

    private static LocalDateTime storageNow() {
        return LocalDateTime.now(STT_STORAGE_OFFSET).truncatedTo(ChronoUnit.SECONDS);
    }

    private static final String GAPS = """
            SELECT id, stt_session_id, event_key, reason, started_at, ended_at FROM stt_gaps WHERE stt_session_id = ?
            """;

    public StartResult start(long roomId, String email, UUID requestKey) {
        Room room = lockOwnedRoom(roomId, email);
        String key = requestKey.toString();
        var previous = jdbc.sql(SESSIONS + "AND start_key = ?").params(roomId, key).query(Session.class).optional();
        if (previous.isPresent()) {
            return new StartResult(false, previous.get().view(key, room.status()));
        }
        if (!"LIVE".equals(room.status())) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        var active = jdbc.sql(SESSIONS + "AND status = 'RECORDING'").param(roomId).query(Session.class).optional();
        if (active.isPresent()) {
            return new StartResult(false, active.get().view(key, room.status()));
        }
        jdbc.sql("""
                INSERT INTO stt_sessions(lecture_room_id, start_key, status, started_at)
                VALUES (?, ?, 'RECORDING', ?)
                """).params(roomId, key, storageNow()).update();
        var created = jdbc.sql(SESSIONS + "AND start_key = ?").params(roomId, key).query(Session.class).single();
        return new StartResult(true, created.view(key, room.status()));
    }

    public SttSessionView stop(long roomId, long sessionId, String email, StopReason reason) {
        Room room = lockOwnedRoom(roomId, email);
        LocalDateTime endedAt = storageNow();
        int stopped = jdbc.sql("""
                UPDATE stt_sessions SET status = ?, ended_at = ?
                WHERE lecture_room_id = ? AND id = ? AND status = 'RECORDING'
                """).params(reason.status, endedAt, roomId, sessionId).update();
        if (stopped == 1) {
            jdbc.sql("UPDATE stt_gaps SET ended_at = ? WHERE stt_session_id = ? AND ended_at IS NULL")
                    .params(endedAt, sessionId).update();
        }
        return jdbc.sql(SESSIONS + "AND id = ?").params(roomId, sessionId).query(Session.class).optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)).view(null, room.status());
    }

    private Session requireRecordingKey(long roomId, long sessionId, UUID startKey) {
        Session session = jdbc.sql(SESSIONS + "AND id = ?").params(roomId, sessionId).query(Session.class).optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!startKey.toString().equals(session.startKey())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return session;
    }

    public GapResult openGap(long roomId, long sessionId, String email, SttGap.OpenRequest request) {
        Instant received = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Room room = lockOwnedRoom(roomId, email);
        Session session = requireRecordingKey(roomId, sessionId, request.startKey());
        Instant start = request.startedAt().truncatedTo(ChronoUnit.SECONDS);
        var byKey = jdbc.sql(GAPS + "AND event_key = ?").params(sessionId, request.eventKey()).query(Gap.class);
        var previous = byKey.optional();
        if (previous.isPresent()) {
            var gap = previous.get().view();
            if (gap.reason() != request.reason() || !gap.startedAt().equals(start)) {
                throw new BusinessException(ErrorCode.CONFLICT);
            }
            return new GapResult(false, gap);
        }
        if (!"LIVE".equals(room.status()) || !"RECORDING".equals(session.status())) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        if (start.isAfter(received) || start.isBefore(session.startedAt().toInstant(STT_STORAGE_OFFSET))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        LocalDateTime startedAt = LocalDateTime.ofInstant(start, STT_STORAGE_OFFSET);
        boolean overlaps = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM stt_gaps WHERE stt_session_id = ? AND (ended_at IS NULL OR ended_at > ?))
                """).params(sessionId, startedAt).query(Boolean.class).single();
        if (overlaps) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        jdbc.sql("INSERT INTO stt_gaps(stt_session_id, event_key, reason, started_at) VALUES (?, ?, ?, ?)")
                .params(sessionId, request.eventKey(), request.reason().name(), startedAt).update();
        return new GapResult(true, byKey.single().view());
    }

    public SttGap.ClosedView closeGap(long roomId, long sessionId, long gapId, String email, SttGap.CloseRequest request) {
        Instant received = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Room room = lockOwnedRoom(roomId, email);
        requireRecordingKey(roomId, sessionId, request.startKey());
        var gap = jdbc.sql(GAPS + "AND id = ?").params(sessionId, gapId).query(Gap.class).optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)).view();
        Instant end = request.endedAt().truncatedTo(ChronoUnit.SECONDS);
        if (gap.endedAt() != null) {
            if (!gap.endedAt().equals(end)) {
                throw new BusinessException(ErrorCode.CONFLICT);
            }
        } else {
            boolean afterRoomEnd = "ENDED".equals(room.status())
                    && (room.endedAt() == null || end.isAfter(room.endedAt().toInstant(STT_STORAGE_OFFSET)));
            if (end.isBefore(gap.startedAt()) || end.isAfter(received) || afterRoomEnd) {
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
            jdbc.sql("UPDATE stt_gaps SET ended_at = ? WHERE stt_session_id = ? AND id = ?")
                    .params(LocalDateTime.ofInstant(end, STT_STORAGE_OFFSET), sessionId, gapId).update();
        }
        return new SttGap.ClosedView(gapId, gap.startedAt(), end, gap.reason());
    }

    public SegmentResult saveSegment(long roomId, long sessionId, String email, SttSegmentRequest request) {
        Room room = lockOwnedRoom(roomId, email);
        Session session = requireRecordingKey(roomId, sessionId, request.startKey());
        if (request.segmentStartAt().isAfter(request.segmentEndAt())
                || request.transcript().getBytes(StandardCharsets.UTF_8).length > 65_535) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        Instant start = request.segmentStartAt().truncatedTo(ChronoUnit.MILLIS);
        Instant end = request.segmentEndAt().truncatedTo(ChronoUnit.MILLIS);
        var existing = findSegment(sessionId, request.segmentKey());
        if (existing.isPresent()) {
            var segment = existing.get();
            if (!segment.transcript().equals(request.transcript())
                    || !segment.segmentStartAt().equals(start) || !segment.segmentEndAt().equals(end)) {
                throw new BusinessException(ErrorCode.CONFLICT);
            }
            return new SegmentResult(false, segment);
        }
        if (!"LIVE".equals(room.status()) || !"RECORDING".equals(session.status())) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        jdbc.sql("""
                INSERT INTO stt_segments(stt_session_id, segment_key, transcript, segment_start_at, segment_end_at, received_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """).params(sessionId, request.segmentKey(), request.transcript(),
                        LocalDateTime.ofInstant(start, STT_STORAGE_OFFSET),
                        LocalDateTime.ofInstant(end, STT_STORAGE_OFFSET),
                        storageNow())
                .update();
        return new SegmentResult(true, findSegment(sessionId, request.segmentKey()).orElseThrow());
    }

    private Optional<SttSegmentView> findSegment(long sessionId, String key) {
        return jdbc.sql("""
                SELECT id, stt_session_id, segment_key, transcript, segment_start_at, segment_end_at,
                       received_at
                FROM stt_segments WHERE stt_session_id = ? AND segment_key = ?
                """).params(sessionId, key).query((row, index) -> new SttSegmentView(
                        row.getLong("id"), row.getLong("stt_session_id"), row.getString("segment_key"),
                        row.getString("transcript"), row.getObject("segment_start_at", LocalDateTime.class).toInstant(STT_STORAGE_OFFSET),
                        row.getObject("segment_end_at", LocalDateTime.class).toInstant(STT_STORAGE_OFFSET),
                        row.getObject("received_at", LocalDateTime.class).toInstant(STT_STORAGE_OFFSET)))
                .optional();
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public SttRecordsView records(long roomId, String email, Instant from, Instant to) {
        Instant requestedAt = Instant.now();
        ownedRoom(roomId, email, false);
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        var segments = recordsQuery("""
                SELECT r.id, r.session_id, r.transcript, r.started_at, r.ended_at, r.received_at FROM (
                    SELECT g.id, g.stt_session_id AS session_id, g.transcript,
                           g.segment_start_at AS started_at, g.segment_end_at AS ended_at, g.received_at
                    FROM stt_segments g JOIN stt_sessions s ON s.id = g.stt_session_id
                    WHERE s.lecture_room_id = :roomId
                ) r WHERE 1=1
                """, roomId, from, to, requestedAt)
                .query((row, index) -> new SttRecordsView.Segment(row.getLong("id"), row.getLong("session_id"),
                        row.getString("transcript"),
                        row.getObject("started_at", LocalDateTime.class).toInstant(STT_STORAGE_OFFSET),
                        row.getObject("ended_at", LocalDateTime.class).toInstant(STT_STORAGE_OFFSET),
                        row.getObject("received_at", LocalDateTime.class).toInstant(STT_STORAGE_OFFSET))).list();
        var gaps = recordsQuery("""
                SELECT r.id, r.stt_session_id AS session_id, r.reason, r.started_at, r.ended_at
                FROM stt_gaps r JOIN stt_sessions s ON s.id = r.stt_session_id
                WHERE s.lecture_room_id = :roomId
                """, roomId, from, to, requestedAt)
                .query((row, index) -> {
                    LocalDateTime endedAt = row.getObject("ended_at", LocalDateTime.class);
                    return new SttRecordsView.Gap(row.getLong("id"), row.getLong("session_id"),
                            SttGap.Reason.valueOf(row.getString("reason")),
                            row.getObject("started_at", LocalDateTime.class).toInstant(STT_STORAGE_OFFSET),
                            endedAt == null ? null : endedAt.toInstant(STT_STORAGE_OFFSET));
                }).list();
        return new SttRecordsView(roomId, segments, gaps);
    }

    private JdbcClient.StatementSpec recordsQuery(String sql, long roomId, Instant from, Instant to, Instant now) {
        var parameters = new HashMap<String, Object>();
        parameters.put("roomId", roomId);
        if (from != null) {
            // A zero-duration record is a point; open gaps extend only to this request's timestamp.
            sql += """
                     AND (COALESCE(r.ended_at, CAST(:now AS DATETIME(6))) > CAST(:from AS DATETIME(6))
                          OR (r.started_at = COALESCE(r.ended_at, CAST(:now AS DATETIME(6)))
                              AND r.started_at >= CAST(:from AS DATETIME(6))))
                    """;
            parameters.put("from", LocalDateTime.ofInstant(from, STT_STORAGE_OFFSET));
            parameters.put("now", LocalDateTime.ofInstant(now, STT_STORAGE_OFFSET));
        }
        if (to != null) {
            sql += " AND r.started_at < :to";
            parameters.put("to", LocalDateTime.ofInstant(to, STT_STORAGE_OFFSET));
        }
        return jdbc.sql(sql + " ORDER BY r.started_at, r.id").params(parameters);
    }

    // Serialize lifecycle changes even when the room has no session row yet.
    private Room lockOwnedRoom(long roomId, String email) {
        return ownedRoom(roomId, email, true);
    }

    private Room ownedRoom(long roomId, String email, boolean lock) {
        String sql = """
                SELECT r.status, u.email, r.ended_at FROM lecture_rooms r JOIN users u ON u.id = r.host_user_id
                WHERE r.id = ?
                """;
        if (lock) {
            sql += " FOR UPDATE OF r";
        }
        Room room = jdbc.sql(sql).param(roomId).query(Room.class).optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!room.email().equalsIgnoreCase(email)) {      // 회원가입시 이메일 대소문자 확정
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return room;
    }
}
