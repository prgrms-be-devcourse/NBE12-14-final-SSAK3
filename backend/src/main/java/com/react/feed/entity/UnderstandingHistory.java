package com.react.feed.entity;

import com.react.feed.dto.UnderstandingStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** 이해 상태가 실제로 바뀔 때마다 한 건씩 추가한다. 최초 선택은 previousStatus 가 null 이다. */
@Entity
@Table(name = "understanding_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UnderstandingHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "participant_id", nullable = false)
    private Long participantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 30)
    private UnderstandingStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 30)
    private UnderstandingStatus newStatus;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    private UnderstandingHistory(Long participantId, UnderstandingStatus previousStatus,
            UnderstandingStatus newStatus, Instant changedAt) {
        this.participantId = participantId;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.changedAt = changedAt;
    }

    public static UnderstandingHistory of(Long participantId, UnderstandingStatus previous,
            UnderstandingStatus next, Instant at) {
        return new UnderstandingHistory(participantId, previous, next, at);
    }
}
