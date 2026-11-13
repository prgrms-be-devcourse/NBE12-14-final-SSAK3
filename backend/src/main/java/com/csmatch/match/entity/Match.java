package com.csmatch.match.entity;

import com.csmatch.matchmaking.entity.MatchType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "matches")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Match {
    public static final int TOTAL_ROUNDS = 10;
    public static final int ROUND_TIME_LIMIT_SECONDS = 20;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "match_id")
    private Long matchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 10)
    private MatchType matchType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MatchStatus status;

    @Column(name = "total_rounds", nullable = false)
    private int totalRounds;

    @Column(name = "round_time_limit_sec", nullable = false)
    private int roundTimeLimitSec;

    @Column(name = "winner_id")
    private Long winnerId;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    private Match(MatchType matchType) {
        this.matchType = matchType;
        status = MatchStatus.PREPARING;
        totalRounds = TOTAL_ROUNDS;
        roundTimeLimitSec = ROUND_TIME_LIMIT_SECONDS;
    }

    public static Match preparing(MatchType matchType) {
        if (matchType == null) {
            throw new IllegalArgumentException("Match type is required");
        }
        return new Match(matchType);
    }
}
