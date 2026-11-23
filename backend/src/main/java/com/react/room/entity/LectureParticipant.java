package com.react.room.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Entity
@Table(name = "lecture_participants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LectureParticipant {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lecture_room_id", nullable = false)
    private LectureRoom lectureRoom;

    @Column(nullable = false, length = 50)
    private String nickname;

    // 쿠키 원본 토큰 대신 해시만 DB에 저장한다.
    @Column(name = "participant_token_hash", nullable = false, unique = true, length = 255)
    private String participantTokenHash;

    // 채팅 수신 시각과 같은 한국 시각 DATETIME을 저장한다.
    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

    public LectureParticipant(LectureRoom lectureRoom, String nickname, String participantTokenHash) {
        this.lectureRoom = lectureRoom;
        this.nickname = nickname;
        this.participantTokenHash = participantTokenHash;
        this.joinedAt = LocalDateTime.now(SEOUL).withNano(0);
    }
}
