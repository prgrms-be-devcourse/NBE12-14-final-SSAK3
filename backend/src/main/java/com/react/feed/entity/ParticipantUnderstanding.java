package com.react.feed.entity;

import com.react.feed.dto.UnderstandingStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** 참여자당 최신 이해 상태 1건. 변경 이력은 {@link UnderstandingHistory} 에 쌓는다. */
@Entity
@Table(name = "participant_understandings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ParticipantUnderstanding {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "participant_id", nullable = false)
    private Long participantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_status", nullable = false, length = 30)
    private UnderstandingStatus currentStatus;

    // Instant 로 들고 있으면 hibernate.jdbc.time_zone(Asia/Seoul) 기준으로 변환되어 JVM 시간대와 무관하게 +09:00 시각이 저장된다.
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private ParticipantUnderstanding(Long participantId, UnderstandingStatus currentStatus, Instant updatedAt) {
        this.participantId = participantId;
        this.currentStatus = currentStatus;
        this.updatedAt = updatedAt;
    }

    public static ParticipantUnderstanding of(Long participantId, UnderstandingStatus status, Instant at) {
        return new ParticipantUnderstanding(participantId, status, at);
    }

    public void changeTo(UnderstandingStatus status, Instant at) {
        this.currentStatus = status;
        this.updatedAt = at;
    }
}
