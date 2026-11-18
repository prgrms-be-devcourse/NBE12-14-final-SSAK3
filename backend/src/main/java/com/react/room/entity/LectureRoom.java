package com.react.room.entity;

import com.react.auth.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Entity
@Table(name = "lecture_rooms")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LectureRoom {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "host_user_id", nullable = false)
    private User host;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "join_code", nullable = false, unique = true, length = 64)
    private String joinCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LectureRoomStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    public LectureRoom(
            User host,
            String title,
            String description,
            String joinCode
    ) {
        this.host = host;
        this.title = title.strip();
        this.description = description == null
                ? null
                : description.strip();
        this.joinCode = joinCode;
        this.status = LectureRoomStatus.READY;
        this.createdAt = LocalDateTime.now(SEOUL);
    }
}