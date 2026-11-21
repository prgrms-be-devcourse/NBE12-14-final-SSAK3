package com.react.report.repository;

import com.react.poll.entity.PollStatus;
import com.react.poll.entity.PollType;
import com.react.poll.entity.UnderstandingStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ReportInputRepository {
    private final JdbcClient jdbc;

    public record Room(String email, String status, LocalDateTime startedAt, LocalDateTime endedAt) {}
    public record Chat(long messageId, long participantId, String content, LocalDateTime receivedAt) {}
    public record Poll(long pollId, PollType type, String question, PollStatus status,
                       LocalDateTime openedAt, LocalDateTime closedAt, Long totalParticipants) {}
    public record Option(long pollId, long optionId, String content, UnderstandingStatus understandingStatus,
                         long responseCount) {}

    public Optional<Room> findRoom(long roomId) {
        return jdbc.sql("""
                SELECT u.email, r.status, r.started_at, r.ended_at
                FROM lecture_rooms r JOIN users u ON u.id = r.host_user_id
                WHERE r.id = ?
                """).param(roomId).query(Room.class).optional();
    }

    public List<Chat> findChats(long roomId, LocalDateTime start, LocalDateTime end) {
        return jdbc.sql("""
                SELECT c.id AS message_id, c.participant_id, c.content, c.received_at
                FROM chat_messages c
                JOIN lecture_participants p ON p.id = c.participant_id AND p.lecture_room_id = c.lecture_room_id
                WHERE c.lecture_room_id = ? AND c.received_at BETWEEN ? AND ?
                ORDER BY c.received_at, c.id
                """).params(roomId, start, end).query(Chat.class).list();
    }

    public List<Poll> findPolls(long roomId) {
        return jdbc.sql("""
                SELECT id AS poll_id, type, question, status, created_at AS opened_at, closed_at,
                       closed_total_participants AS total_participants
                FROM polls WHERE lecture_room_id = ? ORDER BY created_at, id
                """).param(roomId).query(Poll.class).list();
    }

    public List<Option> findOptionCounts(long roomId) {
        return jdbc.sql("""
                SELECT p.id AS poll_id, o.id AS option_id, o.content, o.understanding_status,
                       COUNT(lp.id) AS response_count
                FROM polls p JOIN poll_options o ON o.poll_id = p.id
                LEFT JOIN poll_responses r ON r.poll_id = p.id AND r.poll_option_id = o.id
                LEFT JOIN lecture_participants lp ON lp.id = r.participant_id AND lp.lecture_room_id = p.lecture_room_id
                WHERE p.lecture_room_id = ?
                GROUP BY p.id, o.id, o.content, o.understanding_status, o.display_order
                ORDER BY p.id, o.display_order, o.id
                """).param(roomId).query(Option.class).list();
    }
}
