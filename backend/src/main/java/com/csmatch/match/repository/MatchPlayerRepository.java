package com.csmatch.match.repository;

import com.csmatch.match.entity.MatchPlayer;
import com.csmatch.match.entity.MatchPlayerId;
import com.csmatch.match.entity.MatchStatus;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MatchPlayerRepository extends JpaRepository<MatchPlayer, MatchPlayerId> {
    @Query("""
            select count(player) > 0
            from MatchPlayer player
            where player.id.userId = :userId
              and player.match.status in :statuses
            """)
    boolean existsByUserIdAndMatchStatusIn(
            @Param("userId") Long userId,
            @Param("statuses") Collection<MatchStatus> statuses
    );
}
