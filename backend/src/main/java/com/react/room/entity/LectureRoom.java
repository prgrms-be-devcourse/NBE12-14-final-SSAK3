package com.react.room.entity;

import com.react.auth.entity.User;
import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

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

    // 세 DATETIME 열에는 한국 시각을 저장하므로 JDBC에서 LocalDateTime을 그대로 읽고 쓴다.
    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @ColumnDefault("false")
    @Column(name = "report_receipt_due", nullable = false)
    private boolean reportReceiptDue;

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

    // SCRUM-130: READY 상태에서만 강연 시작
    public void start() {
        if (status != LectureRoomStatus.READY) {
            throw new BusinessException(ErrorCode.INVALID_ROOM_STATUS);
        }

        status = LectureRoomStatus.LIVE;
        startedAt = LocalDateTime.now(SEOUL)
                .truncatedTo(ChronoUnit.SECONDS);
    }

    // SCRUM-130: LIVE 상태에서만 강연 종료
    public void end() {
        if (status != LectureRoomStatus.LIVE) {
            throw new BusinessException(ErrorCode.INVALID_ROOM_STATUS);
        }

        status = LectureRoomStatus.ENDED;
        reportReceiptDue = true;
        endedAt = LocalDateTime.now(SEOUL)
                .truncatedTo(ChronoUnit.SECONDS);
    }

    public void markReportReceived() {
        reportReceiptDue = false;
    }
}
