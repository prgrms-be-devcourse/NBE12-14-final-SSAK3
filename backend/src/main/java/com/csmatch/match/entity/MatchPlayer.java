package com.csmatch.match.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "match_players")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchPlayer {
    @EmbeddedId
    private MatchPlayerId id;

    @MapsId("matchId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "match_id")
    private Match match;

    @Column(name = "ready_at")
    private LocalDateTime readyAt;

    @Column(nullable = false)
    private int score;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private MatchPlayerResult result;

    @Column(name = "rating_before")
    private Integer ratingBefore;

    @Column(name = "rating_after")
    private Integer ratingAfter;

    private MatchPlayer(Match match, Long userId, int ratingBefore) {
        id = new MatchPlayerId(match.getMatchId(), userId);
        this.match = match;
        this.ratingBefore = ratingBefore;
    }

    public static MatchPlayer preparing(Match match, Long userId, int ratingBefore) {
        if (match == null || match.getMatchId() == null || userId == null || userId <= 0) {
            throw new IllegalArgumentException("Persisted match and positive user ID are required");
        }
        return new MatchPlayer(match, userId, ratingBefore);
    }

    public Long getUserId() {
        return id.getUserId();
    }
}
