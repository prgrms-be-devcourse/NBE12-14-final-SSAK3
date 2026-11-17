package com.react.chat.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "chat_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {
    // DATETIME 은 STT 와 같이 고정 +09:00 기준으로 저장한다.
    private static final ZoneOffset STORAGE_OFFSET = ZoneOffset.ofHours(9);

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

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    private ChatMessage(Long lectureRoomId, Long participantId, Long hostUserId, String content) {
        this.lectureRoomId = lectureRoomId;
        this.participantId = participantId;
        this.hostUserId = hostUserId;
        this.content = content;
        // DATETIME 은 초 단위라 DB 가 반올림하지 않도록 미리 초로 자른다. 반환값과 조회값이 같아진다.
        this.receivedAt = LocalDateTime.now(STORAGE_OFFSET).truncatedTo(ChronoUnit.SECONDS);
    }

    public Instant getReceivedAtInstant() {
        return receivedAt.toInstant(STORAGE_OFFSET);
    }

    public static ChatMessage ofParticipant(Long lectureRoomId, Long participantId, String content) {
        return new ChatMessage(lectureRoomId, participantId, null, content);
    }

    public static ChatMessage ofHost(Long lectureRoomId, Long hostUserId, String content) {
        return new ChatMessage(lectureRoomId, null, hostUserId, content);
    }
}
