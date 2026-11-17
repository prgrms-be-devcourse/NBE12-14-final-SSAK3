package com.react.chat.repository;

import com.react.chat.dto.ChatMessageView;
import com.react.chat.dto.ChatSenderView;
import com.react.chat.dto.SenderType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ChatMessageQueryRepository {
    // DATETIME 은 STT 전사 구간과 같이 고정 +09:00 기준으로 저장·해석한다.
    private static final ZoneOffset STORAGE_OFFSET = ZoneOffset.ofHours(9);
    private final JdbcClient jdbc;

    public boolean existsRoom(long roomId) {
        return jdbc.sql("SELECT COUNT(*) FROM lecture_rooms WHERE id = ?")
                .param(roomId).query(Long.class).single() > 0;
    }

    public Optional<String> findRoomStatus(long roomId) {
        return jdbc.sql("SELECT status FROM lecture_rooms WHERE id = ?")
                .param(roomId).query(String.class).optional();
    }

    /** 그 강연방의 참여자면 닉네임을, 아니면 빈 값을 돌려준다. */
    public Optional<String> findParticipantNickname(long roomId, long participantId) {
        return jdbc.sql("SELECT nickname FROM lecture_participants WHERE id = ? AND lecture_room_id = ?")
                .params(participantId, roomId).query(String.class).optional();
    }

    /** 그 강연방의 강연자면 이름을, 아니면 빈 값을 돌려준다. */
    public Optional<String> findHostName(long roomId, long hostUserId) {
        return jdbc.sql("""
                        SELECT u.name FROM lecture_rooms r JOIN users u ON u.id = r.host_user_id
                        WHERE r.id = ? AND r.host_user_id = ?
                        """)
                .params(roomId, hostUserId).query(String.class).optional();
    }

    /** 강연방의 메시지를 최신 순(id DESC)으로 limit 건 조회한다. cursor 가 null 이면 가장 최신부터, 있으면 그 id 보다 오래된 것만. */
    public List<ChatMessageView> findPage(long roomId, Long cursor, int limit) {
        long before = cursor != null ? cursor : Long.MAX_VALUE;
        return jdbc.sql("""
                        SELECT m.id, m.participant_id, m.host_user_id, p.nickname, u.name,
                               m.content, m.received_at
                        FROM chat_messages m
                        LEFT JOIN lecture_participants p ON p.id = m.participant_id
                        LEFT JOIN users u ON u.id = m.host_user_id
                        WHERE m.lecture_room_id = ? AND m.id < ?
                        ORDER BY m.id DESC
                        LIMIT ?
                        """)
                .params(roomId, before, limit)
                .query((rs, rowNum) -> {
                    Long participantId = rs.getObject("participant_id", Long.class);
                    ChatSenderView sender = participantId != null
                            ? new ChatSenderView(SenderType.PARTICIPANT, participantId, rs.getString("nickname"))
                            : new ChatSenderView(SenderType.HOST, rs.getLong("host_user_id"), rs.getString("name"));
                    return new ChatMessageView(rs.getLong("id"), sender, rs.getString("content"),
                            rs.getObject("received_at", LocalDateTime.class).toInstant(STORAGE_OFFSET));
                })
                .list();
    }
}
