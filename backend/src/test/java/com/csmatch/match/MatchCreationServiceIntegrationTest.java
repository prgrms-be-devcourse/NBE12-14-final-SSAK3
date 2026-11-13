package com.csmatch.match;

import com.csmatch.global.exception.BusinessException;
import com.csmatch.global.exception.ErrorCode;
import com.csmatch.match.entity.MatchStatus;
import com.csmatch.match.repository.MatchPlayerRepository;
import com.csmatch.match.repository.MatchRepository;
import com.csmatch.match.service.MatchCreationService;
import com.csmatch.matchmaking.entity.MatchType;
import com.csmatch.user.entity.User;
import com.csmatch.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ActiveProfiles("test")
class MatchCreationServiceIntegrationTest {
    @Autowired
    private MatchCreationService service;
    @Autowired
    private MatchRepository matches;
    @Autowired
    private MatchPlayerRepository matchPlayers;
    @Autowired
    private UserRepository users;
    @Autowired
    private JdbcTemplate jdbc;

    private Long firstUserId;
    private Long secondUserId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM match_players");
        jdbc.update("DELETE FROM matches");
        jdbc.update("DELETE FROM users");
        firstUserId = users.saveAndFlush(User.create("first-match@example.com", "hash", "첫번째회원")).getUserId();
        secondUserId = users.saveAndFlush(User.create("second-match@example.com", "hash", "두번째회원")).getUserId();
        jdbc.update("UPDATE users SET rating = 1100 WHERE user_id = ?", firstUserId);
        jdbc.update("UPDATE users SET rating = 900 WHERE user_id = ?", secondUserId);
    }

    @Test
    void createsPreparingMatchAndTwoPlayersWithRatingSnapshots() {
        var match = service.createPreparingMatch(MatchType.RATED, firstUserId, secondUserId);

        assertThat(match.getMatchId()).isNotNull();
        assertThat(match.getMatchType()).isEqualTo(MatchType.RATED);
        assertThat(match.getStatus()).isEqualTo(MatchStatus.PREPARING);
        assertThat(match.getTotalRounds()).isEqualTo(10);
        assertThat(match.getRoundTimeLimitSec()).isEqualTo(20);
        assertThat(match.getStartedAt()).isNull();
        assertThat(match.getEndedAt()).isNull();
        assertThat(match.getWinnerId()).isNull();

        var players = matchPlayers.findAll().stream().sorted(java.util.Comparator.comparing(player -> player.getUserId()))
                .toList();
        assertThat(players).hasSize(2);
        assertThat(players.get(0).getUserId()).isEqualTo(firstUserId);
        assertThat(players.get(0).getRatingBefore()).isEqualTo(1100);
        assertThat(players.get(1).getUserId()).isEqualTo(secondUserId);
        assertThat(players.get(1).getRatingBefore()).isEqualTo(900);
        assertThat(players).allSatisfy(player -> {
            assertThat(player.getReadyAt()).isNull();
            assertThat(player.getScore()).isZero();
            assertThat(player.getResult()).isNull();
            assertThat(player.getRatingAfter()).isNull();
        });
    }

    @Test
    void activeMatchExistsForPreparingAndPlayingOnly() {
        var match = service.createPreparingMatch(MatchType.UNRATED, firstUserId, secondUserId);

        assertThat(service.hasActiveMatch(firstUserId)).isTrue();
        jdbc.update("UPDATE matches SET status = 'PLAYING' WHERE match_id = ?", match.getMatchId());
        assertThat(service.hasActiveMatch(firstUserId)).isTrue();
        jdbc.update("UPDATE matches SET status = 'FINISHED' WHERE match_id = ?", match.getMatchId());
        assertThat(service.hasActiveMatch(firstUserId)).isFalse();
    }

    @Test
    void refusesToCreateAnotherActiveMatchForEitherPlayer() {
        service.createPreparingMatch(MatchType.RATED, firstUserId, secondUserId);

        assertThatThrownBy(() -> service.createPreparingMatch(MatchType.UNRATED, firstUserId, secondUserId))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.MATCH_IN_PROGRESS);
        assertThat(matches.count()).isEqualTo(1);
        assertThat(matchPlayers.count()).isEqualTo(2);
    }

    @Test
    void refusesSameUserBeforePersistingMatch() {
        assertThatThrownBy(() -> service.createPreparingMatch(MatchType.RATED, firstUserId, firstUserId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(matches.count()).isZero();
        assertThat(matchPlayers.count()).isZero();
    }
}
