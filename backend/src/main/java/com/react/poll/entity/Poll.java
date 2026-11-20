package com.react.poll.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "polls")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Poll {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lecture_room_id", nullable = false)
    private Long lectureRoomId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PollType type;

    @Column(nullable = false, length = 500)
    private String question;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PollStatus status;

    // Instant 로 들고 있으면 hibernate.jdbc.time_zone(Asia/Seoul) 기준으로 변환되어 JVM 시간대와 무관하게 +09:00 시각이 저장된다.
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "closes_at", nullable = false)
    private Instant closesAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "closed_at")
    private Instant closedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "close_reason", length = 20)
    private PollCloseReason closeReason;

    @Column(name = "closed_total_participants")
    private Long closedTotalParticipants;

    @OneToMany(mappedBy = "poll", cascade = CascadeType.PERSIST)
    @OrderBy("displayOrder")
    private List<PollOption> options = new ArrayList<>();

    private Poll(Long lectureRoomId, PollType type, String question, Instant now, int durationSeconds) {
        this.lectureRoomId = lectureRoomId;
        this.type = type;
        this.question = question;
        this.status = PollStatus.OPEN;
        this.createdAt = now;
        this.closesAt = now.plusSeconds(durationSeconds);
    }

    /** now 는 DATETIME 이 초 단위라 DB 가 반올림하지 않도록 호출하는 쪽에서 초로 자른 값이다. */
    public static Poll general(Long lectureRoomId, String question, List<String> contents, Instant now, int durationSeconds) {
        Poll poll = new Poll(lectureRoomId, PollType.GENERAL, question, now, durationSeconds);
        for (int i = 0; i < contents.size(); i++) {
            poll.options.add(new PollOption(poll, contents.get(i), i + 1, null));
        }
        return poll;
    }

    public static Poll understanding(Long lectureRoomId, String question, Instant now, int durationSeconds) {
        Poll poll = new Poll(lectureRoomId, PollType.UNDERSTANDING, question, now, durationSeconds);
        int order = 1;
        for (UnderstandingStatus status : UnderstandingStatus.values()) {
            poll.options.add(new PollOption(poll, status.getLabel(), order++, status));
        }
        return poll;
    }

    /** 마감 시각이 지났는지. closesAt 정각부터 응답을 받지 않는다. */
    public boolean isExpired(Instant now) {
        return !now.isBefore(closesAt);
    }

    /** status 가 OPEN 이어도 마감 시각이 지났으면 CLOSED 로 본다. */
    public PollStatus effectiveStatus(Instant now) {
        return status == PollStatus.OPEN && isExpired(now) ? PollStatus.CLOSED : status;
    }

    public void closeByHost(Instant now, long totalParticipants) {
        this.closedTotalParticipants = totalParticipants;
        this.status = PollStatus.CLOSED;
        this.closedAt = now;
        this.closeReason = PollCloseReason.HOST;
    }

    /** 강연이 종료되어 마감한다. 호출하는 쪽이 이미 제한 시간이 지난 투표는 expire() 로 처리한다. */
    public void closeByRoomEnded(Instant now, long totalParticipants) {
        this.closedTotalParticipants = totalParticipants;
        this.status = PollStatus.CLOSED;
        this.closedAt = now;
        this.closeReason = PollCloseReason.ROOM_ENDED;
    }

    /** 제한 시간이 지나 마감한다. 마감 시각은 실제 처리 시각이 아니라 closesAt 이다. */
    public void expire(long totalParticipants) {
        this.closedTotalParticipants = totalParticipants;
        this.status = PollStatus.CLOSED;
        this.closedAt = closesAt;
        this.closeReason = PollCloseReason.TIMEOUT;
    }
}
