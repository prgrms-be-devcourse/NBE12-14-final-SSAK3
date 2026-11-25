package com.react.chat.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "chat_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lecture_room_id", nullable = false)
    private Long lectureRoomId;

    // participant_id 와 host_user_id 중 정확히 하나만 존재한다 (DB CHECK 와 같은 규칙).
    @Column(name = "participant_id")
    private Long participantId;

    @Column(name = "host_user_id")
    private Long hostUserId;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    // Instant 로 들고 있으면 hibernate.jdbc.time_zone(Asia/Seoul) 기준으로 변환되어 JVM 시간대와 무관하게 +09:00 시각이 저장된다.
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    // 화면이 전송마다 만든 UUID. 같은 작성자의 같은 값은 한 번만 저장한다. 식별자 없이 온 요청은 null 이다.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "client_message_id", length = 36)
    private String clientMessageId;

    private ChatMessage(Long lectureRoomId, Long participantId, Long hostUserId, String content, String clientMessageId) {
        this.lectureRoomId = lectureRoomId;
        this.participantId = participantId;
        this.hostUserId = hostUserId;
        this.content = content;
        this.clientMessageId = clientMessageId;
        // DATETIME 은 초 단위라 DB 가 반올림하지 않도록 미리 초로 자른다. 반환값과 조회값이 같아진다.
        this.receivedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    public static ChatMessage ofParticipant(Long lectureRoomId, Long participantId, String content, String clientMessageId) {
        return new ChatMessage(lectureRoomId, participantId, null, content, clientMessageId);
    }

    public static ChatMessage ofHost(Long lectureRoomId, Long hostUserId, String content, String clientMessageId) {
        return new ChatMessage(lectureRoomId, null, hostUserId, content, clientMessageId);
    }
}
