package com.csmatch.matchmaking.repository;

import com.csmatch.global.exception.BusinessException;
import com.csmatch.match.entity.MatchStatus;
import com.csmatch.match.repository.MatchPlayerRepository;
import com.csmatch.match.repository.MatchRepository;
import com.csmatch.match.service.MatchCreationService;
import com.csmatch.matchmaking.dto.ConfirmedMatchResult;
import com.csmatch.matchmaking.dto.ReservedMatchPair;
import com.csmatch.matchmaking.entity.MatchType;
import com.csmatch.matchmaking.entity.MatchmakingStatus;
import com.csmatch.matchmaking.service.MatchmakingSearchScheduler;
import com.csmatch.matchmaking.service.MatchmakingService;
import com.csmatch.user.entity.User;
import com.csmatch.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ActiveProfiles("test")
class MatchmakingServiceMatchCreationIntegrationTest {
    private static final String KEY_PREFIX = "test:csmatch:matchmaking:creation";
    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");

    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private MatchCreationService matchCreationService;
    @Autowired
    private MatchRepository matches;
    @Autowired
    private MatchPlayerRepository matchPlayers;
    @Autowired
    private UserRepository users;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private MatchmakingRedisRepository repository;
    private MatchmakingService service;
    private MatchmakingSearchScheduler scheduler;
    private Long firstUserId;
    private Long secondUserId;

    @BeforeEach
    void setUp() {
        repository = new MatchmakingRedisRepository(redisTemplate, KEY_PREFIX);
        service = new MatchmakingService(repository, matchCreationService);
        scheduler = new MatchmakingSearchScheduler(repository, service);
        clearTestKeys();
        jdbc.update("DELETE FROM match_players");
        jdbc.update("DELETE FROM matches");
        jdbc.update("DELETE FROM users");
        firstUserId = createUser("first-matchmaking@example.com", "첫번째매칭회원", 1_100);
        secondUserId = createUser("second-matchmaking@example.com", "두번째매칭회원", 1_000);
    }

    @AfterEach
    void tearDown() {
        clearTestKeys();
    }

    @Test
    void createsCommittedPreparingMatchAndConfirmsBothRedisStates() {
        repository.registerWaiting(firstUserId, MatchType.RATED, 1_100, NOW);
        repository.registerWaiting(secondUserId, MatchType.RATED, 1_000, NOW);

        ConfirmedMatchResult result = service.tryCreateAndConfirmMatch(firstUserId, NOW).orElseThrow();

        assertThat(result.matchId()).isPositive();
        assertThat(matches.count()).isEqualTo(1);
        assertThat(matchPlayers.count()).isEqualTo(2);
        var match = matches.findById(result.matchId()).orElseThrow();
        assertThat(match.getStatus()).isEqualTo(MatchStatus.PREPARING);
        assertThat(match.getMatchType()).isEqualTo(MatchType.RATED);
        var players = matchPlayers.findAll().stream().sorted(java.util.Comparator.comparing(player -> player.getUserId()))
                .toList();
        assertThat(players).extracting(player -> player.getRatingBefore()).containsExactlyInAnyOrder(1_000, 1_100);
        assertThat(repository.findState(firstUserId).status()).isEqualTo(MatchmakingStatus.MATCHED);
        assertThat(repository.findState(secondUserId).status()).isEqualTo(MatchmakingStatus.MATCHED);
        assertThat(repository.findState(firstUserId).matchId()).isEqualTo(result.matchId());
        assertThat(repository.findState(secondUserId).matchId()).isEqualTo(result.matchId());
        assertThat(redisTemplate.hasKey(KEY_PREFIX + ":reservation:" + firstUserId)).isFalse();
        assertThat(redisTemplate.hasKey(KEY_PREFIX + ":reservation:" + secondUserId)).isFalse();
    }

    @Test
    void resolvesActiveUserAndRestoresOnlyTheUserWithoutAnActiveMatch() {
        var activeMatch = matchCreationService.createPreparingMatch(MatchType.UNRATED, firstUserId, secondUserId);
        Long thirdUserId = createUser("third-matchmaking@example.com", "세번째매칭회원", 1_000);
        repository.registerWaiting(firstUserId, MatchType.RATED, 1_100, NOW);
        repository.registerWaiting(thirdUserId, MatchType.RATED, 1_000, NOW);

        assertThatThrownBy(() -> service.tryCreateAndConfirmMatch(firstUserId, NOW))
                .isInstanceOf(BusinessException.class);

        assertThat(matches.count()).isEqualTo(1);
        assertThat(matchPlayers.count()).isEqualTo(2);
        assertThat(repository.findState(firstUserId).status()).isEqualTo(MatchmakingStatus.MATCHED);
        assertThat(repository.findState(firstUserId).matchId()).isEqualTo(activeMatch.getMatchId());
        assertThat(repository.findState(thirdUserId).status()).isEqualTo(MatchmakingStatus.WAITING);
        assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":queue:rated", firstUserId.toString())).isNull();
        assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":queue:rated", thirdUserId.toString())).isEqualTo(1_000D);
    }

    @Test
    void createsMatchInNewTransactionBeforeReturningToAnOuterTransaction() {
        repository.registerWaiting(firstUserId, MatchType.UNRATED, 1_100, NOW);
        repository.registerWaiting(secondUserId, MatchType.UNRATED, 1_000, NOW);

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(service.tryCreateAndConfirmMatch(firstUserId, NOW)).isPresent();
            status.setRollbackOnly();
        });

        assertThat(matches.count()).isEqualTo(1);
        assertThat(matchPlayers.count()).isEqualTo(2);
    }

    @Test
    void concurrentRequestsSharingOneUserCreateOnlyOneMatch() throws Exception {
        jdbc.update("UPDATE users SET rating = 900 WHERE user_id = ?", firstUserId);
        jdbc.update("UPDATE users SET rating = 1000 WHERE user_id = ?", secondUserId);
        Long thirdUserId = createUser("third-concurrent@example.com", "동시성세번째회원", 1_100);
        repository.registerWaiting(firstUserId, MatchType.RATED, 900, NOW);
        repository.registerWaiting(secondUserId, MatchType.RATED, 1_000, NOW);
        repository.registerWaiting(thirdUserId, MatchType.RATED, 1_100, NOW);
        CyclicBarrier barrier = new CyclicBarrier(2);

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Optional<ConfirmedMatchResult>>> tasks = List.of(
                    () -> createAfterBarrier(barrier, firstUserId),
                    () -> createAfterBarrier(barrier, thirdUserId));

            List<Optional<ConfirmedMatchResult>> results = executor.invokeAll(tasks).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .toList();

            assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
            assertThat(matches.count()).isEqualTo(1);
            assertThat(matchPlayers.count()).isEqualTo(2);
        }
    }

    @Test
    void schedulerMatchesUsersAfterTheirRatedRangeExpandsWithoutResettingWaitingState() {
        Instant joinedAt = NOW;
        jdbc.update("UPDATE users SET rating = 1000 WHERE user_id = ?", firstUserId);
        jdbc.update("UPDATE users SET rating = 1300 WHERE user_id = ?", secondUserId);
        repository.registerWaiting(firstUserId, MatchType.RATED, 1_000, joinedAt);
        repository.registerWaiting(secondUserId, MatchType.RATED, 1_300, joinedAt);

        scheduler.rematchRatedWaiters(joinedAt);
        assertThat(matches.count()).isZero();
        assertThat(repository.findState(firstUserId).joinedAt()).isEqualTo(joinedAt);
        assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":queue:rated", firstUserId.toString()))
                .isEqualTo(1_000D);

        scheduler.rematchRatedWaiters(joinedAt.plusSeconds(10));
        assertThat(matches.count()).isZero();

        scheduler.rematchRatedWaiters(joinedAt.plusSeconds(20));
        assertThat(matches.count()).isEqualTo(1);
        assertThat(repository.findState(firstUserId).status()).isEqualTo(MatchmakingStatus.MATCHED);
        assertThat(repository.findState(secondUserId).status()).isEqualTo(MatchmakingStatus.MATCHED);
    }

    @Test
    void schedulerUsesTheMaximumRatedRangeAfterFortySeconds() {
        Instant joinedAt = NOW;
        jdbc.update("UPDATE users SET rating = 1000 WHERE user_id = ?", firstUserId);
        jdbc.update("UPDATE users SET rating = 1500 WHERE user_id = ?", secondUserId);
        repository.registerWaiting(firstUserId, MatchType.RATED, 1_000, joinedAt);
        repository.registerWaiting(secondUserId, MatchType.RATED, 1_500, joinedAt);

        scheduler.rematchRatedWaiters(joinedAt.plusSeconds(39));
        assertThat(matches.count()).isZero();

        scheduler.rematchRatedWaiters(joinedAt.plusSeconds(40));
        assertThat(matches.count()).isEqualTo(1);
    }

    @Test
    void schedulerDoesNotCreateMatchesForReservedOrMatchedUsers() {
        repository.registerWaiting(firstUserId, MatchType.RATED, 1_100, NOW);
        repository.registerWaiting(secondUserId, MatchType.RATED, 1_000, NOW);
        ReservedMatchPair reservation = repository.reservePair(firstUserId, secondUserId, MatchType.RATED).orElseThrow();

        scheduler.rematchRatedWaiters(NOW);
        assertThat(matches.count()).isZero();

        assertThat(repository.releaseReservation(reservation)).isTrue();
        assertThat(service.tryCreateAndConfirmMatch(firstUserId, NOW)).isPresent();
        scheduler.rematchRatedWaiters(NOW.plusSeconds(10));

        assertThat(matches.count()).isEqualTo(1);
        assertThat(matchPlayers.count()).isEqualTo(2);
    }

    @Test
    void schedulerAndDirectMatchRequestCreateOnlyOneMatch() throws Exception {
        jdbc.update("UPDATE users SET rating = 900 WHERE user_id = ?", firstUserId);
        jdbc.update("UPDATE users SET rating = 1000 WHERE user_id = ?", secondUserId);
        Long thirdUserId = createUser("third-scheduler@example.com", "스케줄러세번째회원", 1_100);
        repository.registerWaiting(firstUserId, MatchType.RATED, 900, NOW);
        repository.registerWaiting(secondUserId, MatchType.RATED, 1_000, NOW);
        repository.registerWaiting(thirdUserId, MatchType.RATED, 1_100, NOW);
        CyclicBarrier barrier = new CyclicBarrier(2);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var schedulerFuture = executor.submit(() -> {
                barrier.await();
                scheduler.rematchRatedWaiters(NOW);
                return null;
            });
            var requestFuture = executor.submit(() -> {
                barrier.await();
                return service.tryCreateAndConfirmMatch(thirdUserId, NOW);
            });

            schedulerFuture.get();
            requestFuture.get();
        }

        assertThat(matches.count()).isEqualTo(1);
        assertThat(matchPlayers.count()).isEqualTo(2);
    }

    private Optional<ConfirmedMatchResult> createAfterBarrier(CyclicBarrier barrier, long requesterId) throws Exception {
        barrier.await();
        return service.tryCreateAndConfirmMatch(requesterId, NOW);
    }

    private Long createUser(String email, String nickname, int rating) {
        Long userId = users.saveAndFlush(User.create(email, "hash", nickname)).getUserId();
        jdbc.update("UPDATE users SET rating = ? WHERE user_id = ?", rating, userId);
        return userId;
    }

    private void clearTestKeys() {
        var keys = redisTemplate.keys(KEY_PREFIX + ":*");
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
