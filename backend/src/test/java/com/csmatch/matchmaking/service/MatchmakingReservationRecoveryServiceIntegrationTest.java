package com.csmatch.matchmaking.service;

import com.csmatch.match.entity.MatchStatus;
import com.csmatch.match.repository.MatchPlayerRepository;
import com.csmatch.match.repository.MatchRepository;
import com.csmatch.match.service.MatchCreationService;
import com.csmatch.matchmaking.entity.MatchType;
import com.csmatch.matchmaking.entity.MatchmakingStatus;
import com.csmatch.matchmaking.entity.ReservationRecoveryResult;
import com.csmatch.matchmaking.repository.MatchmakingRedisRepository;
import com.csmatch.user.entity.User;
import com.csmatch.user.repository.UserRepository;
import java.time.Instant;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ActiveProfiles("test")
class MatchmakingReservationRecoveryServiceIntegrationTest {
    private static final String KEY_PREFIX = "test:csmatch:matchmaking:recovery";
    private static final Instant JOINED_AT = Instant.parse("2026-11-13T00:00:00Z");

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

    private MatchmakingRedisRepository repository;
    private MatchmakingReservationRecoveryService recoveryService;
    private MatchmakingService matchmakingService;
    private MatchmakingReservationRecoveryScheduler recoveryScheduler;
    private MatchmakingSearchScheduler ratedSearchScheduler;
    private long firstUserId;
    private long secondUserId;

    @BeforeEach
    void setUp() {
        repository = new MatchmakingRedisRepository(redisTemplate, KEY_PREFIX);
        recoveryService = new MatchmakingReservationRecoveryService(repository, matchCreationService);
        matchmakingService = new MatchmakingService(repository, matchCreationService);
        recoveryScheduler = new MatchmakingReservationRecoveryScheduler(repository, recoveryService);
        ratedSearchScheduler = new MatchmakingSearchScheduler(repository, matchmakingService);
        clearTestKeys();
        jdbc.update("DELETE FROM match_players");
        jdbc.update("DELETE FROM matches");
        jdbc.update("DELETE FROM users");
        firstUserId = createUser("recovery-first@example.com", "복구첫사용자", 1_100);
        secondUserId = createUser("recovery-second@example.com", "복구둘사용자", 1_000);
    }

    @AfterEach
    void tearDown() {
        clearTestKeys();
    }

    @Test
    void restoresExpiredReservationToOriginalRatedQueueWhenNoActiveMatchExists() {
        repository.registerWaiting(firstUserId, MatchType.RATED, 1_100, JOINED_AT);
        repository.registerWaiting(secondUserId, MatchType.RATED, 1_000, JOINED_AT.plusSeconds(5));
        var reservation = repository.reservePair(firstUserId, secondUserId, MatchType.RATED).orElseThrow();
        expireReservation(reservation.reservationToken());

        ReservationRecoveryResult result = recoveryService.recoverExpiredReservation(
                reservation.reservationToken(), JOINED_AT.plusSeconds(61));

        assertThat(result).isEqualTo(ReservationRecoveryResult.RECOVERED);
        assertThat(matches.count()).isZero();
        assertThat(repository.findState(firstUserId))
                .extracting(state -> state.status(), state -> state.rating(), state -> state.joinedAt())
                .containsExactly(MatchmakingStatus.WAITING, 1_100, JOINED_AT);
        assertThat(repository.findState(secondUserId))
                .extracting(state -> state.status(), state -> state.rating(), state -> state.joinedAt())
                .containsExactly(MatchmakingStatus.WAITING, 1_000, JOINED_AT.plusSeconds(5));
        assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":queue:rated", Long.toString(firstUserId)))
                .isEqualTo(1_100D);
        assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":queue:rated", Long.toString(secondUserId)))
                .isEqualTo(1_000D);
        assertReservationCleaned(reservation.reservationToken());
    }

    @Test
    void marksUsersMatchedWithExistingActiveMatchInsteadOfCreatingAnotherMatch() {
        var activeMatch = matchCreationService.createPreparingMatch(MatchType.UNRATED, firstUserId, secondUserId);
        repository.registerWaiting(firstUserId, MatchType.RATED, 1_100, JOINED_AT);
        repository.registerWaiting(secondUserId, MatchType.RATED, 1_000, JOINED_AT);
        var reservation = repository.reservePair(firstUserId, secondUserId, MatchType.RATED).orElseThrow();
        expireReservation(reservation.reservationToken());

        ReservationRecoveryResult result = recoveryService.recoverExpiredReservation(
                reservation.reservationToken(), JOINED_AT.plusSeconds(61));

        assertThat(result).isEqualTo(ReservationRecoveryResult.RECOVERED);
        assertThat(matches.count()).isEqualTo(1);
        assertThat(matches.findById(activeMatch.getMatchId()).orElseThrow().getStatus()).isEqualTo(MatchStatus.PREPARING);
        assertThat(repository.findState(firstUserId))
                .extracting(state -> state.status(), state -> state.matchType(), state -> state.matchId())
                .containsExactly(MatchmakingStatus.MATCHED, MatchType.UNRATED, activeMatch.getMatchId());
        assertThat(repository.findState(secondUserId))
                .extracting(state -> state.status(), state -> state.matchType(), state -> state.matchId())
                .containsExactly(MatchmakingStatus.MATCHED, MatchType.UNRATED, activeMatch.getMatchId());
        assertReservationCleaned(reservation.reservationToken());
    }

    @Test
    void leavesStateUntouchedWhenRecoveryMetadataIsMissing() {
        repository.registerWaiting(firstUserId, MatchType.UNRATED, 1_100, JOINED_AT);
        repository.registerWaiting(secondUserId, MatchType.UNRATED, 1_000, JOINED_AT);
        var reservation = repository.reservePair(firstUserId, secondUserId, MatchType.UNRATED).orElseThrow();
        expireReservation(reservation.reservationToken());
        redisTemplate.delete(reservationDataKey(reservation.reservationToken()));

        ReservationRecoveryResult result = recoveryService.recoverExpiredReservation(
                reservation.reservationToken(), JOINED_AT.plusSeconds(61));

        assertThat(result).isEqualTo(ReservationRecoveryResult.NOT_RECOVERABLE);
        assertThat(repository.findState(firstUserId).status()).isEqualTo(MatchmakingStatus.WAITING);
        assertThat(repository.findState(secondUserId).status()).isEqualTo(MatchmakingStatus.WAITING);
        assertThat(redisTemplate.hasKey(KEY_PREFIX + ":reservation:" + firstUserId)).isTrue();
        assertThat(redisTemplate.hasKey(KEY_PREFIX + ":reservation:" + secondUserId)).isTrue();
    }

    @Test
    void expiredCreationLeaseCannotLetDelayedMatchCreationStartAfterRecovery() {
        repository.registerWaiting(firstUserId, MatchType.RATED, 1_100, JOINED_AT);
        repository.registerWaiting(secondUserId, MatchType.RATED, 1_000, JOINED_AT);
        var reservation = repository.reservePair(firstUserId, secondUserId, MatchType.RATED).orElseThrow();
        assertThat(repository.beginMatchCreation(reservation)).isTrue();
        redisTemplate.delete(creationLeaseKey(reservation.reservationToken()));
        expireReservation(reservation.reservationToken());

        assertThat(recoveryService.recoverExpiredReservation(reservation.reservationToken(), JOINED_AT.plusSeconds(61)))
                .isEqualTo(ReservationRecoveryResult.RECOVERED);

        assertThat(repository.beginMatchCreation(reservation)).isFalse();
        assertThat(matches.count()).isZero();
    }

    @Test
    void normalMatchCanStartAfterRecoveryReleasedUserLocksAndRestoredQueue() {
        repository.registerWaiting(firstUserId, MatchType.RATED, 1_100, JOINED_AT);
        repository.registerWaiting(secondUserId, MatchType.RATED, 1_000, JOINED_AT);
        var reservation = repository.reservePair(firstUserId, secondUserId, MatchType.RATED).orElseThrow();
        expireReservation(reservation.reservationToken());

        assertThat(recoveryService.recoverExpiredReservation(reservation.reservationToken(), JOINED_AT.plusSeconds(61)))
                .isEqualTo(ReservationRecoveryResult.RECOVERED);
        assertThat(matchmakingService.tryCreateAndConfirmMatch(firstUserId, JOINED_AT.plusSeconds(61))).isPresent();

        assertThat(matches.count()).isEqualTo(1);
        assertThat(repository.findState(firstUserId).status()).isEqualTo(MatchmakingStatus.MATCHED);
        assertThat(repository.findState(secondUserId).status()).isEqualTo(MatchmakingStatus.MATCHED);
    }

    @Test
    void recoversOnlyInactiveUserWhenOtherUserAlreadyHasAnActiveMatch() {
        long thirdUserId = createUser("recovery-third@example.com", "복구세사용자", 900);
        var activeMatch = matchCreationService.createPreparingMatch(MatchType.UNRATED, firstUserId, thirdUserId);
        repository.registerWaiting(firstUserId, MatchType.RATED, 1_100, JOINED_AT);
        repository.registerWaiting(secondUserId, MatchType.RATED, 1_000, JOINED_AT);
        var reservation = repository.reservePair(firstUserId, secondUserId, MatchType.RATED).orElseThrow();
        expireReservation(reservation.reservationToken());

        assertThat(recoveryService.recoverExpiredReservation(reservation.reservationToken(), JOINED_AT.plusSeconds(61)))
                .isEqualTo(ReservationRecoveryResult.RECOVERED);

        assertThat(repository.findState(firstUserId))
                .extracting(state -> state.status(), state -> state.matchId())
                .containsExactly(MatchmakingStatus.MATCHED, activeMatch.getMatchId());
        assertThat(repository.findState(secondUserId))
                .extracting(state -> state.status(), state -> state.rating(), state -> state.joinedAt())
                .containsExactly(MatchmakingStatus.WAITING, 1_000, JOINED_AT);
        assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":queue:rated", Long.toString(firstUserId))).isNull();
        assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":queue:rated", Long.toString(secondUserId)))
                .isEqualTo(1_000D);
    }

    @Test
    void restoresUnratedReservationToOriginalQueue() {
        repository.registerWaiting(firstUserId, MatchType.UNRATED, 1_100, JOINED_AT);
        repository.registerWaiting(secondUserId, MatchType.UNRATED, 1_000, JOINED_AT.plusSeconds(5));
        var reservation = repository.reservePair(firstUserId, secondUserId, MatchType.UNRATED).orElseThrow();
        expireReservation(reservation.reservationToken());

        assertThat(recoveryService.recoverExpiredReservation(reservation.reservationToken(), JOINED_AT.plusSeconds(61)))
                .isEqualTo(ReservationRecoveryResult.RECOVERED);

        assertThat(repository.findState(firstUserId).joinedAt()).isEqualTo(JOINED_AT);
        assertThat(repository.findState(secondUserId).joinedAt()).isEqualTo(JOINED_AT.plusSeconds(5));
        assertThat(redisTemplate.opsForSet().isMember(KEY_PREFIX + ":queue:unrated", Long.toString(firstUserId))).isTrue();
        assertThat(redisTemplate.opsForSet().isMember(KEY_PREFIX + ":queue:unrated", Long.toString(secondUserId))).isTrue();
    }

    @Test
    void completedConfirmationCannotBeOverwrittenByStaleRecoveryExecution() {
        repository.registerWaiting(firstUserId, MatchType.RATED, 1_100, JOINED_AT);
        repository.registerWaiting(secondUserId, MatchType.RATED, 1_000, JOINED_AT);
        var reservation = repository.reservePair(firstUserId, secondUserId, MatchType.RATED).orElseThrow();
        expireReservation(reservation.reservationToken());
        assertThat(repository.claimExpiredReservation(reservation.reservationToken(), "stale-execution", JOINED_AT)).isTrue();
        var data = repository.findReservationRecoveryData(reservation.reservationToken()).orElseThrow();

        assertThat(repository.confirmMatch(reservation, 700L)).isTrue();
        assertThat(repository.resolveExpiredReservation(data, "stale-execution", null, null, null, null)).isFalse();

        assertThat(repository.findState(firstUserId).matchId()).isEqualTo(700L);
        assertThat(repository.findState(secondUserId).matchId()).isEqualTo(700L);
        assertThat(redisTemplate.hasKey(recoveryLockKey(reservation.reservationToken()))).isFalse();
    }

    @Test
    void incompleteMetadataStopsRecoveryWithoutChangingRedisState() {
        repository.registerWaiting(firstUserId, MatchType.UNRATED, 1_100, JOINED_AT);
        repository.registerWaiting(secondUserId, MatchType.UNRATED, 1_000, JOINED_AT);
        var reservation = repository.reservePair(firstUserId, secondUserId, MatchType.UNRATED).orElseThrow();
        expireReservation(reservation.reservationToken());
        redisTemplate.opsForHash().delete(reservationDataKey(reservation.reservationToken()), "firstRating");

        assertThat(recoveryService.recoverExpiredReservation(reservation.reservationToken(), JOINED_AT.plusSeconds(61)))
                .isEqualTo(ReservationRecoveryResult.INVALID_METADATA);

        assertThat(repository.findState(firstUserId).status()).isEqualTo(MatchmakingStatus.WAITING);
        assertThat(repository.findState(secondUserId).status()).isEqualTo(MatchmakingStatus.WAITING);
        assertThat(redisTemplate.opsForSet().isMember(KEY_PREFIX + ":queue:unrated", Long.toString(firstUserId))).isFalse();
        assertThat(redisTemplate.opsForSet().isMember(KEY_PREFIX + ":queue:unrated", Long.toString(secondUserId))).isFalse();
        assertThat(redisTemplate.hasKey(reservationDataKey(reservation.reservationToken()))).isTrue();
    }

    @Test
    void expiredRecoveryLockCannotLetOlderRecoveryExecutionApplyItsDecision() {
        repository.registerWaiting(firstUserId, MatchType.RATED, 1_100, JOINED_AT);
        repository.registerWaiting(secondUserId, MatchType.RATED, 1_000, JOINED_AT);
        var reservation = repository.reservePair(firstUserId, secondUserId, MatchType.RATED).orElseThrow();
        expireReservation(reservation.reservationToken());
        assertThat(repository.claimExpiredReservation(reservation.reservationToken(), "first-execution", JOINED_AT)).isTrue();
        redisTemplate.delete(recoveryLockKey(reservation.reservationToken()));
        assertThat(repository.claimExpiredReservation(reservation.reservationToken(), "second-execution", JOINED_AT)).isTrue();
        var data = repository.findReservationRecoveryData(reservation.reservationToken()).orElseThrow();

        assertThat(repository.resolveExpiredReservation(data, "first-execution", null, null, null, null)).isFalse();
        assertThat(repository.resolveExpiredReservation(data, "second-execution", null, null, null, null)).isTrue();

        assertThat(repository.findState(firstUserId).status()).isEqualTo(MatchmakingStatus.WAITING);
        assertThat(repository.findState(secondUserId).status()).isEqualTo(MatchmakingStatus.WAITING);
    }

    @Test
    void schedulerDoesNotProcessReservationBeforeItsExpirationTime() {
        repository.registerWaiting(firstUserId, MatchType.RATED, 1_100, JOINED_AT);
        repository.registerWaiting(secondUserId, MatchType.RATED, 1_000, JOINED_AT);
        var reservation = repository.reservePair(firstUserId, secondUserId, MatchType.RATED).orElseThrow();

        recoveryScheduler.recoverExpiredReservations(Instant.now());

        assertThat(repository.findState(firstUserId).status()).isEqualTo(MatchmakingStatus.WAITING);
        assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":queue:rated", Long.toString(firstUserId))).isNull();
        assertThat(redisTemplate.hasKey(reservationDataKey(reservation.reservationToken()))).isTrue();
    }

    @Test
    void schedulerRestoresExpiredUnratedReservationAndDoesNotProcessItAgain() {
        repository.registerWaiting(firstUserId, MatchType.UNRATED, 1_100, JOINED_AT);
        repository.registerWaiting(secondUserId, MatchType.UNRATED, 1_000, JOINED_AT.plusSeconds(5));
        var reservation = repository.reservePair(firstUserId, secondUserId, MatchType.UNRATED).orElseThrow();
        expireReservation(reservation.reservationToken());

        recoveryScheduler.recoverExpiredReservations(JOINED_AT.plusSeconds(61));
        recoveryScheduler.recoverExpiredReservations(JOINED_AT.plusSeconds(71));

        assertThat(redisTemplate.opsForSet().isMember(KEY_PREFIX + ":queue:unrated", Long.toString(firstUserId))).isTrue();
        assertThat(redisTemplate.opsForSet().isMember(KEY_PREFIX + ":queue:unrated", Long.toString(secondUserId))).isTrue();
        assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":reservation:expires", reservation.reservationToken()))
                .isNull();
    }

    @Test
    void schedulerProcessesAtMostFiftyExpiredTokensPerExecution() {
        for (int index = 0; index < 51; index++) {
            redisTemplate.opsForZSet().add(KEY_PREFIX + ":reservation:expires", "missing-" + index,
                    JOINED_AT.minusSeconds(1).toEpochMilli());
        }

        recoveryScheduler.recoverExpiredReservations(JOINED_AT);

        assertThat(redisTemplate.opsForZSet().zCard(KEY_PREFIX + ":reservation:expires")).isEqualTo(1L);
        assertThat(redisTemplate.opsForZSet().zCard(KEY_PREFIX + ":reservation:invalid")).isEqualTo(50L);
    }

    @Test
    void invalidMetadataDoesNotBlockSchedulerFromRecoveringOtherReservation() {
        repository.registerWaiting(firstUserId, MatchType.RATED, 1_100, JOINED_AT);
        repository.registerWaiting(secondUserId, MatchType.RATED, 1_000, JOINED_AT);
        var invalidReservation = repository.reservePair(firstUserId, secondUserId, MatchType.RATED).orElseThrow();
        expireReservation(invalidReservation.reservationToken());
        redisTemplate.opsForHash().delete(reservationDataKey(invalidReservation.reservationToken()), "firstRating");

        long thirdUserId = createUser("scheduler-third@example.com", "스케줄러세사용자", 900);
        long fourthUserId = createUser("scheduler-fourth@example.com", "스케줄러네사용자", 900);
        repository.registerWaiting(thirdUserId, MatchType.UNRATED, 900, JOINED_AT);
        repository.registerWaiting(fourthUserId, MatchType.UNRATED, 900, JOINED_AT);
        var validReservation = repository.reservePair(thirdUserId, fourthUserId, MatchType.UNRATED).orElseThrow();
        expireReservation(validReservation.reservationToken());

        recoveryScheduler.recoverExpiredReservations(JOINED_AT.plusSeconds(61));

        assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":reservation:invalid", invalidReservation.reservationToken()))
                .isNotNull();
        assertThat(redisTemplate.opsForSet().isMember(KEY_PREFIX + ":queue:unrated", Long.toString(thirdUserId))).isTrue();
        assertThat(redisTemplate.opsForSet().isMember(KEY_PREFIX + ":queue:unrated", Long.toString(fourthUserId))).isTrue();
    }

    @Test
    void schedulerMarksUsersMatchedWhenAnActiveMatchAlreadyExists() {
        var activeMatch = matchCreationService.createPreparingMatch(MatchType.UNRATED, firstUserId, secondUserId);
        repository.registerWaiting(firstUserId, MatchType.RATED, 1_100, JOINED_AT);
        repository.registerWaiting(secondUserId, MatchType.RATED, 1_000, JOINED_AT);
        var reservation = repository.reservePair(firstUserId, secondUserId, MatchType.RATED).orElseThrow();
        expireReservation(reservation.reservationToken());

        recoveryScheduler.recoverExpiredReservations(JOINED_AT.plusSeconds(61));

        assertThat(matches.count()).isEqualTo(1);
        assertThat(repository.findState(firstUserId).matchId()).isEqualTo(activeMatch.getMatchId());
        assertThat(repository.findState(secondUserId).matchId()).isEqualTo(activeMatch.getMatchId());
    }

    @Test
    void schedulerAndNormalMatchRequestDoNotCreateDuplicateMatch() throws Exception {
        repository.registerWaiting(firstUserId, MatchType.RATED, 1_100, JOINED_AT);
        repository.registerWaiting(secondUserId, MatchType.RATED, 1_000, JOINED_AT);
        var reservation = repository.reservePair(firstUserId, secondUserId, MatchType.RATED).orElseThrow();
        expireReservation(reservation.reservationToken());
        CyclicBarrier barrier = new CyclicBarrier(2);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var recovery = executor.submit(() -> {
                barrier.await();
                recoveryScheduler.recoverExpiredReservations(JOINED_AT.plusSeconds(61));
                return null;
            });
            var matching = executor.submit(() -> {
                barrier.await();
                return matchmakingService.tryCreateAndConfirmMatch(firstUserId, JOINED_AT.plusSeconds(61));
            });
            recovery.get();
            matching.get();
        }

        assertTerminalReservationState(reservation);
    }

    @Test
    void recoveryAndRatedSearchSchedulersDoNotCreateDuplicateMatch() throws Exception {
        repository.registerWaiting(firstUserId, MatchType.RATED, 1_100, JOINED_AT);
        repository.registerWaiting(secondUserId, MatchType.RATED, 1_000, JOINED_AT);
        var reservation = repository.reservePair(firstUserId, secondUserId, MatchType.RATED).orElseThrow();
        expireReservation(reservation.reservationToken());
        CyclicBarrier barrier = new CyclicBarrier(2);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var recovery = executor.submit(() -> {
                barrier.await();
                recoveryScheduler.recoverExpiredReservations(JOINED_AT.plusSeconds(61));
                return null;
            });
            var ratedSearch = executor.submit(() -> {
                barrier.await();
                ratedSearchScheduler.rematchRatedWaiters(JOINED_AT.plusSeconds(61));
                return null;
            });
            recovery.get();
            ratedSearch.get();
        }

        assertTerminalReservationState(reservation);
    }

    private void assertTerminalReservationState(com.csmatch.matchmaking.dto.ReservedMatchPair reservation) {
        if (matches.count() == 1) {
            var match = matches.findAll().getFirst();
            assertThat(matchPlayers.count()).isEqualTo(2);
            assertThat(repository.findState(firstUserId))
                    .extracting(state -> state.status(), state -> state.matchId())
                    .containsExactly(MatchmakingStatus.MATCHED, match.getMatchId());
            assertThat(repository.findState(secondUserId))
                    .extracting(state -> state.status(), state -> state.matchId())
                    .containsExactly(MatchmakingStatus.MATCHED, match.getMatchId());
            assertReservationCleaned(reservation.reservationToken());
        } else {
            assertThat(matches.count()).isZero();
            assertThat(matchPlayers.count()).isZero();
            assertThat(repository.findState(firstUserId).status()).isEqualTo(MatchmakingStatus.WAITING);
            assertThat(repository.findState(secondUserId).status()).isEqualTo(MatchmakingStatus.WAITING);
            assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":queue:rated", Long.toString(firstUserId)))
                    .isEqualTo(1_100D);
            assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":queue:rated", Long.toString(secondUserId)))
                    .isEqualTo(1_000D);
            assertReservationCleaned(reservation.reservationToken());
        }
    }

    private void expireReservation(String reservationToken) {
        redisTemplate.opsForZSet().add(KEY_PREFIX + ":reservation:expires", reservationToken,
                JOINED_AT.minusSeconds(1).toEpochMilli());
    }

    private void assertReservationCleaned(String reservationToken) {
        assertThat(redisTemplate.hasKey(reservationDataKey(reservationToken))).isFalse();
        assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":reservation:expires", reservationToken)).isNull();
        assertThat(redisTemplate.hasKey(KEY_PREFIX + ":reservation:" + firstUserId)).isFalse();
        assertThat(redisTemplate.hasKey(KEY_PREFIX + ":reservation:" + secondUserId)).isFalse();
        assertThat(redisTemplate.hasKey(creationLeaseKey(reservationToken))).isFalse();
        assertThat(redisTemplate.hasKey(recoveryLockKey(reservationToken))).isFalse();
    }

    private String reservationDataKey(String reservationToken) {
        return KEY_PREFIX + ":reservation:data:" + reservationToken;
    }

    private String creationLeaseKey(String reservationToken) {
        return KEY_PREFIX + ":reservation:creation:" + reservationToken;
    }

    private String recoveryLockKey(String reservationToken) {
        return KEY_PREFIX + ":reservation:recovery:" + reservationToken;
    }

    private long createUser(String email, String nickname, int rating) {
        long userId = users.saveAndFlush(User.create(email, "hash", nickname)).getUserId();
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
