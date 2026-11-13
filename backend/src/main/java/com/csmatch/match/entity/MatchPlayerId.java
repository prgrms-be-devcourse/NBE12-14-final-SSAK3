package com.csmatch.match.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchPlayerId implements Serializable {
    @Column(name = "match_id")
    private Long matchId;

    @Column(name = "user_id")
    private Long userId;

    public MatchPlayerId(Long matchId, Long userId) {
        this.matchId = matchId;
        this.userId = userId;
    }
}
