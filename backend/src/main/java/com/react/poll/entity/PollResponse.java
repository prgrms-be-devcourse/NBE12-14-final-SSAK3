package com.react.poll.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** 청중 한 명의 투표 응답. 투표당 1건이며 마감 전에 다시 보내면 선택을 바꾼다(변경 이력은 남기지 않는다). */
@Entity
@Table(name = "poll_responses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PollResponse {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "poll_id", nullable = false)
    private Long pollId;

    @Column(name = "poll_option_id", nullable = false)
    private Long pollOptionId;

    @Column(name = "participant_id", nullable = false)
    private Long participantId;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "responded_at", nullable = false)
    private Instant respondedAt;

    public PollResponse(Long pollId, Long pollOptionId, Long participantId, Instant respondedAt) {
        this.pollId = pollId;
        this.pollOptionId = pollOptionId;
        this.participantId = participantId;
        this.respondedAt = respondedAt;
    }

    /** 다른 선택지로 바꾼다. 같은 선택지면 호출하지 않으므로 respondedAt 은 마지막으로 선택이 바뀐 시각이다. */
    public void changeOption(Long pollOptionId, Instant now) {
        this.pollOptionId = pollOptionId;
        this.respondedAt = now;
    }
}
