package com.react.stt.service;

import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.stt.dto.SttSessionView;
import com.react.stt.dto.SttSegmentRequest;
import com.react.stt.dto.SttSegmentView;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class SttSessionService {
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
    private record Room(String status, String email) {}
    private record Session(long id, long roomId, String status, long startedAt, Long endedAt, String startKey) {
        SttSessionView view(String key) {
            return new SttSessionView(id, roomId, status, Instant.ofEpochSecond(startedAt),
                    endedAt == null ? null : Instant.ofEpochSecond(endedAt),
                    key != null && key.equals(startKey) && "RECORDING".equals(status));
        }
    }

    private static final String SESSIONS = """
            SELECT id, lecture_room_id AS room_id, status, UNIX_TIMESTAMP(started_at) AS started_at,
                   UNIX_TIMESTAMP(ended_at) AS ended_at, start_key FROM stt_sessions WHERE lecture_room_id = ?
            """;

    public StartResult start(long roomId, String email, UUID requestKey) {
        Room room = lockOwnedRoom(roomId, email);
        String key = requestKey.toString();
        var previous = jdbc.sql(SESSIONS + "AND start_key = ?").params(roomId, key).query(Session.class).optional();
        if (previous.isPresent()) {
            return new StartResult(false, previous.get().view(key));
        }
        if (!"LIVE".equals(room.status())) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        var active = jdbc.sql(SESSIONS + "AND status = 'RECORDING'").param(roomId).query(Session.class).optional();
        if (active.isPresent()) {
            return new StartResult(false, active.get().view(key));
        }
        jdbc.sql("""
                INSERT INTO stt_sessions(lecture_room_id, start_key, status, started_at)
                VALUES (?, ?, 'RECORDING', CURRENT_TIMESTAMP)
                """).params(roomId, key).update();
        var created = jdbc.sql(SESSIONS + "AND start_key = ?").params(roomId, key).query(Session.class).single();
        return new StartResult(true, created.view(key));
    }

    public SttSessionView stop(long roomId, long sessionId, String email, StopReason reason) {
        lockOwnedRoom(roomId, email);
        jdbc.sql("""
                UPDATE stt_sessions SET status = ?, ended_at = CURRENT_TIMESTAMP
                WHERE lecture_room_id = ? AND id = ? AND status = 'RECORDING'
                """).params(reason.status, roomId, sessionId).update();
        return jdbc.sql(SESSIONS + "AND id = ?").params(roomId, sessionId).query(Session.class).optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)).view(null);
    }

    public SegmentResult saveSegment(long roomId, long sessionId, String email, SttSegmentRequest request) {
        Room room = lockOwnedRoom(roomId, email);
        Session session = jdbc.sql(SESSIONS + "AND id = ?").params(roomId, sessionId).query(Session.class).optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!request.startKey().toString().equals(session.startKey())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
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
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """).params(sessionId, request.segmentKey(), request.transcript(), Timestamp.from(start), Timestamp.from(end))
                .update();
        return new SegmentResult(true, findSegment(sessionId, request.segmentKey()).orElseThrow());
    }

    private Optional<SttSegmentView> findSegment(long sessionId, String key) {
        return jdbc.sql("""
                SELECT id, stt_session_id, segment_key, transcript, segment_start_at, segment_end_at,
                       UNIX_TIMESTAMP(received_at) AS received_epoch
                FROM stt_segments WHERE stt_session_id = ? AND segment_key = ?
                """).params(sessionId, key).query((row, index) -> new SttSegmentView(
                        row.getLong("id"), row.getLong("stt_session_id"), row.getString("segment_key"),
                        row.getString("transcript"), row.getTimestamp("segment_start_at").toInstant(),
                        row.getTimestamp("segment_end_at").toInstant(), Instant.ofEpochSecond(row.getLong("received_epoch"))))
                .optional();
    }

    // Serialize lifecycle changes even when the room has no session row yet.
    private Room lockOwnedRoom(long roomId, String email) {
        Room room = jdbc.sql("""
                SELECT r.status, u.email FROM lecture_rooms r JOIN users u ON u.id = r.host_user_id
                WHERE r.id = ? FOR UPDATE OF r
                """).param(roomId).query(Room.class).optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!room.email().equalsIgnoreCase(email)) {      // 회원가입시 이메일 대소문자 확정
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return room;
    }
}
