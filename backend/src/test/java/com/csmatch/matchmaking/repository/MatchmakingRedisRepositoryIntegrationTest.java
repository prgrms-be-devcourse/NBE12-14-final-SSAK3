package com.csmatch.matchmaking.repository;

import com.csmatch.matchmaking.entity.MatchType;
import com.csmatch.matchmaking.entity.MatchmakingStatus;
import com.csmatch.matchmaking.entity.QueueRegistrationResult;
import com.csmatch.matchmaking.entity.QueueRemovalResult;
import com.csmatch.matchmaking.dto.MatchCandidate;
import com.csmatch.matchmaking.dto.ReservedMatchPair;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class MatchmakingRedisRepositoryIntegrationTest {
    private static final String KEY_PREFIX = "test:csmatch:matchmaking";
    private static final Instant JOINED_AT = Instant.parse("2026-11-12T00:00:00Z");

    private StringRedisTemplate redisTemplate;
    private LettuceConnectionFactory connectionFactory;

    private MatchmakingRedisRepository repository;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(System.getenv().getOrDefault("REDIS_HOST", "localhost"), 6379);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        repository = new MatchmakingRedisRepository(redisTemplate, KEY_PREFIX);
        clearTestKeys();
    }

    @AfterEach
    void tearDown() {
        clearTestKeys();
        connectionFactory.destroy();
    }

    @Test
    void registersRatedWaitingStateAndStoresRatingInSortedSet() {
        QueueRegistrationResult result = repository.registerWaiting(101L, MatchType.RATED, 1_250, JOINED_AT);

        assertThat(result).isEqualTo(QueueRegistrationResult.REGISTERED);
        var state = repository.findState(101L);
        assertThat(state.status()).isEqualTo(MatchmakingStatus.WAITING);
        assertThat(state.matchType()).isEqualTo(MatchType.RATED);
        assertThat(state.rating()).isEqualTo(1_250);
        assertThat(state.joinedAt()).isEqualTo(JOINED_AT);
        assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":queue:rated", "101")).isEqualTo(1_250D);
    }

    @Test
    void registersUnratedWaitingStateAndStoresUserInSet() {
        QueueRegistrationResult result = repository.registerWaiting(102L, MatchType.UNRATED, 999, JOINED_AT);

        assertThat(result).isEqualTo(QueueRegistrationResult.REGISTERED);
        assertThat(redisTemplate.opsForSet().isMember(KEY_PREFIX + ":queue:unrated", "102")).isTrue();
        assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":queue:rated", "102")).isNull();
    }

    @Test
    void duplicateRegistrationDoesNotOverwriteOriginalWaitingState() {
        repository.registerWaiting(103L, MatchType.RATED, 1_000, JOINED_AT);

        QueueRegistrationResult result = repository.registerWaiting(103L, MatchType.UNRATED, 1_500,
                JOINED_AT.plusSeconds(10));

        assertThat(result).isEqualTo(QueueRegistrationResult.ALREADY_WAITING);
        var state = repository.findState(103L);
        assertThat(state.matchType()).isEqualTo(MatchType.RATED);
        assertThat(state.rating()).isEqualTo(1_000);
        assertThat(state.joinedAt()).isEqualTo(JOINED_AT);
    }

    @Test
    void registrationLeavesOrphanedWaitingStateUnchangedWhenQueueMemberIsMissing() {
        redisTemplate.opsForHash().putAll(KEY_PREFIX + ":state:107", java.util.Map.of(
                "status", "WAITING",
                "matchType", "RATED",
                "rating", "1000",
                "joinedAtEpochMillis", Long.toString(JOINED_AT.toEpochMilli())));

        QueueRegistrationResult result = repository.registerWaiting(107L, MatchType.RATED, 1_000, JOINED_AT);

        assertThat(result).isEqualTo(QueueRegistrationResult.ALREADY_WAITING);
        assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":queue:rated", "107")).isNull();
    }

    @Test
    void registrationAllowsNegativeRating() {
        QueueRegistrationResult result = repository.registerWaiting(108L, MatchType.RATED, -1, JOINED_AT);

        assertThat(result).isEqualTo(QueueRegistrationResult.REGISTERED);
        assertThat(repository.findState(108L).rating()).isEqualTo(-1);
        assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":queue:rated", "108")).isEqualTo(-1D);
    }

    @Test
    void removesWaitingStateFromItsQueue() {
        repository.registerWaiting(104L, MatchType.UNRATED, 1_000, JOINED_AT);

        QueueRemovalResult result = repository.removeWaiting(104L);

        assertThat(result).isEqualTo(QueueRemovalResult.REMOVED);
        assertThat(repository.findState(104L).status()).isEqualTo(MatchmakingStatus.IDLE);
        assertThat(redisTemplate.opsForSet().isMember(KEY_PREFIX + ":queue:unrated", "104")).isFalse();
        assertThat(repository.removeWaiting(104L)).isEqualTo(QueueRemovalResult.NOT_IN_QUEUE);
    }

    @Test
    void doesNotRemoveMatchedState() {
        String stateKey = KEY_PREFIX + ":state:105";
        redisTemplate.opsForHash().putAll(stateKey, java.util.Map.of(
                "status", "MATCHED",
                "matchType", "RATED",
                "matchId", "55"));

        assertThat(repository.removeWaiting(105L)).isEqualTo(QueueRemovalResult.ALREADY_MATCHED);
        assertThat(repository.findState(105L).status()).isEqualTo(MatchmakingStatus.MATCHED);
        assertThat(repository.registerWaiting(105L, MatchType.RATED, 1_000, JOINED_AT))
                .isEqualTo(QueueRegistrationResult.ALREADY_MATCHED);
    }

    @Test
    void concurrentRegistrationOfSameUserCreatesOneWaitingEntry() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<QueueRegistrationResult>> tasks = java.util.stream.IntStream.range(0, 20)
                    .<Callable<QueueRegistrationResult>>mapToObj(index ->
                            () -> repository.registerWaiting(106L, MatchType.RATED, 1_000, JOINED_AT))
                    .toList();

            List<QueueRegistrationResult> results = executor.invokeAll(tasks).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .toList();

            assertThat(results).containsExactlyInAnyOrder(
                    QueueRegistrationResult.REGISTERED,
                    QueueRegistrationResult.ALREADY_WAITING,
                    QueueRegistrationResult.ALREADY_WAITING,
                    QueueRegistrationResult.ALREADY_WAITING,
                    QueueRegistrationResult.ALREADY_WAITING,
                    QueueRegistrationResult.ALREADY_WAITING,
                    QueueRegistrationResult.ALREADY_WAITING,
                    QueueRegistrationResult.ALREADY_WAITING,
                    QueueRegistrationResult.ALREADY_WAITING,
                    QueueRegistrationResult.ALREADY_WAITING,
                    QueueRegistrationResult.ALREADY_WAITING,
                    QueueRegistrationResult.ALREADY_WAITING,
                    QueueRegistrationResult.ALREADY_WAITING,
                    QueueRegistrationResult.ALREADY_WAITING,
                    QueueRegistrationResult.ALREADY_WAITING,
                    QueueRegistrationResult.ALREADY_WAITING,
                    QueueRegistrationResult.ALREADY_WAITING,
                    QueueRegistrationResult.ALREADY_WAITING,
                    QueueRegistrationResult.ALREADY_WAITING,
                    QueueRegistrationResult.ALREADY_WAITING);
            assertThat(redisTemplate.opsForZSet().zCard(KEY_PREFIX + ":queue:rated")).isEqualTo(1L);
        }
    }

    @Test
    void findsRatedCandidatesOnlyWhenBothUsersAcceptTheRatingDifference() {
        Instant now = JOINED_AT.plusSeconds(20);
        repository.registerWaiting(201L, MatchType.RATED, 1_000, JOINED_AT);
        repository.registerWaiting(202L, MatchType.RATED, 1_300, JOINED_AT);
        repository.registerWaiting(203L, MatchType.RATED, 1_301, JOINED_AT);
        repository.registerWaiting(204L, MatchType.RATED, 1_200, now);

        List<MatchCandidate> candidates = repository.findRatedCandidates(201L, now);

        assertThat(candidates).extracting(MatchCandidate::userId).containsExactly(202L);
    }

    @Test
    void findsOnlyValidUnratedCandidateAndExcludesRequester() {
        repository.registerWaiting(211L, MatchType.UNRATED, 1_000, JOINED_AT);
        repository.registerWaiting(212L, MatchType.UNRATED, 2_000, JOINED_AT);
        repository.registerWaiting(213L, MatchType.UNRATED, 1_500, JOINED_AT);
        repository.registerWaiting(214L, MatchType.RATED, 1_000, JOINED_AT);
        redisTemplate.delete(KEY_PREFIX + ":state:213");

        Optional<MatchCandidate> candidate = repository.findUnratedCandidate(211L);

        assertThat(candidate).isPresent();
        assertThat(candidate.orElseThrow().userId()).isEqualTo(212L);
    }

    @Test
    void reservesBothWaitingUsersAtomicallyAndCanReleaseTheReservation() {
        repository.registerWaiting(221L, MatchType.RATED, 1_000, JOINED_AT);
        repository.registerWaiting(222L, MatchType.RATED, 1_000, JOINED_AT);

        ReservedMatchPair reservation = repository.reservePair(221L, 222L, MatchType.RATED).orElseThrow();

        assertThat(repository.findState(221L).status()).isEqualTo(MatchmakingStatus.WAITING);
        assertThat(repository.findState(222L).status()).isEqualTo(MatchmakingStatus.WAITING);
        assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":queue:rated", "221")).isNull();
        assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":queue:rated", "222")).isNull();
        assertThat(repository.removeWaiting(221L)).isEqualTo(QueueRemovalResult.RESERVED);

        assertThat(repository.beginMatchCreation(reservation)).isTrue();
        assertThat(repository.releaseReservation(reservation)).isTrue();
        assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":queue:rated", "221")).isEqualTo(1_000D);
        assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":queue:rated", "222")).isEqualTo(1_000D);
        assertReservationMetadataIsRemoved(reservation);
    }

    @Test
    void storesRecoveryMetadataAndExpirationIndexWhenReservingPair() {
        repository.registerWaiting(219L, MatchType.RATED, 1_000, JOINED_AT);
        repository.registerWaiting(220L, MatchType.RATED, 1_250, JOINED_AT.plusSeconds(5));
        long beforeReservation = Instant.now().toEpochMilli();

        ReservedMatchPair reservation = repository.reservePair(219L, 220L, MatchType.RATED).orElseThrow();

        String dataKey = reservationDataKey(reservation);
        assertThat(redisTemplate.opsForHash().entries(dataKey)).containsEntry("token", reservation.reservationToken())
                .containsEntry("firstUserId", "219")
                .containsEntry("secondUserId", "220")
                .containsEntry("matchType", "RATED")
                .containsEntry("firstRating", "1000")
                .containsEntry("secondRating", "1250")
                .containsEntry("firstJoinedAtEpochMillis", Long.toString(JOINED_AT.toEpochMilli()))
                .containsEntry("secondJoinedAtEpochMillis", Long.toString(JOINED_AT.plusSeconds(5).toEpochMilli()));
        Double expiresAt = redisTemplate.opsForZSet().score(reservationExpirationKey(), reservation.reservationToken());
        assertThat(expiresAt).isNotNull();
        assertThat(expiresAt.longValue()).isBetween(beforeReservation + 59_000L, beforeReservation + 61_000L);
        assertThat(redisTemplate.opsForHash().entries(dataKey)).containsEntry("expiresAtEpochMillis",
                Long.toString(expiresAt.longValue()));
        assertThat(redisTemplate.getExpire(reservationKey(219L))).isNegative();
        assertThat(redisTemplate.getExpire(reservationKey(220L))).isNegative();
    }

    @Test
    void confirmsReservationAsMatchedAndStoresTheSameMatchIdForBothUsers() {
        repository.registerWaiting(225L, MatchType.UNRATED, 1_000, JOINED_AT);
        repository.registerWaiting(226L, MatchType.UNRATED, 1_500, JOINED_AT);
        ReservedMatchPair reservation = repository.reservePair(225L, 226L, MatchType.UNRATED).orElseThrow();

        assertThat(repository.beginMatchCreation(reservation)).isTrue();
        assertThat(repository.confirmMatch(reservation, 901L)).isTrue();
        assertThat(repository.findState(225L))
                .extracting(state -> state.status(), state -> state.matchType(), state -> state.matchId())
                .containsExactly(MatchmakingStatus.MATCHED, MatchType.UNRATED, 901L);
        assertThat(repository.findState(226L))
                .extracting(state -> state.status(), state -> state.matchType(), state -> state.matchId())
                .containsExactly(MatchmakingStatus.MATCHED, MatchType.UNRATED, 901L);
        assertThat(redisTemplate.opsForSet().isMember(KEY_PREFIX + ":queue:unrated", "225")).isFalse();
        assertThat(redisTemplate.opsForSet().isMember(KEY_PREFIX + ":queue:unrated", "226")).isFalse();
        assertThat(redisTemplate.hasKey(KEY_PREFIX + ":reservation:225")).isFalse();
        assertThat(redisTemplate.hasKey(KEY_PREFIX + ":reservation:226")).isFalse();
        assertReservationMetadataIsRemoved(reservation);
    }

    @Test
    void doesNotConfirmReservationWithWrongToken() {
        repository.registerWaiting(227L, MatchType.RATED, 1_000, JOINED_AT);
        repository.registerWaiting(228L, MatchType.RATED, 1_000, JOINED_AT);
        ReservedMatchPair reservation = repository.reservePair(227L, 228L, MatchType.RATED).orElseThrow();
        ReservedMatchPair wrongToken = new ReservedMatchPair(227L, 228L, MatchType.RATED, "wrong-token");

        assertThat(repository.confirmMatch(wrongToken, 902L)).isFalse();
        assertThat(repository.findState(227L).status()).isEqualTo(MatchmakingStatus.WAITING);
        assertThat(repository.findState(228L).status()).isEqualTo(MatchmakingStatus.WAITING);
        assertThat(redisTemplate.hasKey(KEY_PREFIX + ":reservation:227")).isTrue();
        assertThat(redisTemplate.hasKey(KEY_PREFIX + ":reservation:228")).isTrue();
        assertThat(repository.confirmMatch(reservation, 902L)).isTrue();
    }

    @Test
    void confirmsTheSameMatchIdIdempotentlyButRejectsAnotherMatchId() {
        repository.registerWaiting(229L, MatchType.RATED, 1_000, JOINED_AT);
        repository.registerWaiting(230L, MatchType.RATED, 1_000, JOINED_AT);
        ReservedMatchPair reservation = repository.reservePair(229L, 230L, MatchType.RATED).orElseThrow();

        assertThat(repository.confirmMatch(reservation, 903L)).isTrue();
        assertThat(repository.confirmMatch(reservation, 903L)).isTrue();
        assertThat(repository.confirmMatch(reservation, 904L)).isFalse();
        assertThat(repository.findState(229L).matchId()).isEqualTo(903L);
        assertThat(repository.findState(230L).matchId()).isEqualTo(903L);
    }

    @Test
    void doesNotConfirmAnAlreadyMatchedPairWithAnotherReservationToken() {
        repository.registerWaiting(237L, MatchType.RATED, 1_000, JOINED_AT);
        repository.registerWaiting(238L, MatchType.RATED, 1_000, JOINED_AT);
        ReservedMatchPair reservation = repository.reservePair(237L, 238L, MatchType.RATED).orElseThrow();

        assertThat(repository.confirmMatch(reservation, 907L)).isTrue();
        assertThat(repository.confirmMatch(
                new ReservedMatchPair(237L, 238L, MatchType.RATED, "wrong-token"), 907L)).isFalse();
        assertThat(repository.findState(237L).matchId()).isEqualTo(907L);
        assertThat(repository.findState(238L).matchId()).isEqualTo(907L);
    }

    @Test
    void doesNotConfirmAReleasedReservation() {
        repository.registerWaiting(233L, MatchType.UNRATED, 1_000, JOINED_AT);
        repository.registerWaiting(234L, MatchType.UNRATED, 1_000, JOINED_AT);
        ReservedMatchPair reservation = repository.reservePair(233L, 234L, MatchType.UNRATED).orElseThrow();

        assertThat(repository.releaseReservation(reservation)).isTrue();
        assertThat(repository.confirmMatch(reservation, 905L)).isFalse();
        assertThat(repository.findState(233L).status()).isEqualTo(MatchmakingStatus.WAITING);
        assertThat(repository.findState(234L).status()).isEqualTo(MatchmakingStatus.WAITING);
        assertThat(redisTemplate.opsForSet().isMember(KEY_PREFIX + ":queue:unrated", "233")).isTrue();
        assertThat(redisTemplate.opsForSet().isMember(KEY_PREFIX + ":queue:unrated", "234")).isTrue();
    }

    @Test
    void resolvesOnlyTheUserWithAnExistingActiveMatchAndRestoresTheOtherUser() {
        repository.registerWaiting(235L, MatchType.RATED, 1_000, JOINED_AT);
        repository.registerWaiting(236L, MatchType.RATED, 1_100, JOINED_AT);
        ReservedMatchPair reservation = repository.reservePair(235L, 236L, MatchType.RATED).orElseThrow();

        assertThat(repository.resolveReservationAfterActiveMatches(
                reservation, 906L, MatchType.UNRATED, null, null)).isTrue();
        assertThat(repository.findState(235L))
                .extracting(state -> state.status(), state -> state.matchType(), state -> state.matchId())
                .containsExactly(MatchmakingStatus.MATCHED, MatchType.UNRATED, 906L);
        assertThat(repository.findState(236L).status()).isEqualTo(MatchmakingStatus.WAITING);
        assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":queue:rated", "235")).isNull();
        assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":queue:rated", "236")).isEqualTo(1_100D);
        assertThat(redisTemplate.hasKey(KEY_PREFIX + ":reservation:235")).isFalse();
        assertThat(redisTemplate.hasKey(KEY_PREFIX + ":reservation:236")).isFalse();
        assertReservationMetadataIsRemoved(reservation);
    }

    @Test
    void staleRecoveryExecutionCannotResolveAReservationClaimedByNewerExecution() {
        repository.registerWaiting(239L, MatchType.RATED, 1_000, JOINED_AT);
        repository.registerWaiting(240L, MatchType.RATED, 1_100, JOINED_AT.plusSeconds(5));
        ReservedMatchPair reservation = repository.reservePair(239L, 240L, MatchType.RATED).orElseThrow();
        redisTemplate.opsForZSet().add(reservationExpirationKey(), reservation.reservationToken(),
                JOINED_AT.minusSeconds(1).toEpochMilli());

        assertThat(repository.claimExpiredReservation(reservation.reservationToken(), "first-execution", JOINED_AT)).isTrue();
        redisTemplate.delete(KEY_PREFIX + ":reservation:recovery:" + reservation.reservationToken());
        assertThat(repository.claimExpiredReservation(reservation.reservationToken(), "second-execution", JOINED_AT)).isTrue();

        var data = repository.findReservationRecoveryData(reservation.reservationToken()).orElseThrow();
        assertThat(repository.resolveExpiredReservation(data, "first-execution", null, null, null, null)).isFalse();
        assertThat(repository.resolveExpiredReservation(data, "second-execution", null, null, null, null)).isTrue();
        assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":queue:rated", "239")).isEqualTo(1_000D);
        assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":queue:rated", "240")).isEqualTo(1_100D);
    }

    @Test
    void recoveryClaimPreventsStaleMatchCreationFromStarting() {
        repository.registerWaiting(244L, MatchType.UNRATED, 1_000, JOINED_AT);
        repository.registerWaiting(245L, MatchType.UNRATED, 1_100, JOINED_AT);
        ReservedMatchPair reservation = repository.reservePair(244L, 245L, MatchType.UNRATED).orElseThrow();
        redisTemplate.opsForZSet().add(reservationExpirationKey(), reservation.reservationToken(),
                JOINED_AT.minusSeconds(1).toEpochMilli());

        assertThat(repository.claimExpiredReservation(reservation.reservationToken(), "recovery-execution", JOINED_AT)).isTrue();
        assertThat(repository.beginMatchCreation(reservation)).isFalse();
    }

    @Test
    void quarantinesOnlyReservationStillPresentInExpirationIndex() {
        repository.registerWaiting(246L, MatchType.UNRATED, 1_000, JOINED_AT);
        repository.registerWaiting(247L, MatchType.UNRATED, 1_100, JOINED_AT);
        ReservedMatchPair reservation = repository.reservePair(246L, 247L, MatchType.UNRATED).orElseThrow();
        redisTemplate.opsForZSet().add(reservationExpirationKey(), reservation.reservationToken(),
                JOINED_AT.minusSeconds(1).toEpochMilli());

        assertThat(repository.quarantineReservation(reservation.reservationToken(), JOINED_AT)).isTrue();
        assertThat(redisTemplate.opsForZSet().score(reservationExpirationKey(), reservation.reservationToken())).isNull();
        assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":reservation:invalid", reservation.reservationToken()))
                .isNotNull();
        assertThat(repository.quarantineReservation(reservation.reservationToken(), JOINED_AT)).isFalse();
    }

    @Test
    void doesNotQuarantineTokenAlreadyCompletedByConfirmation() {
        repository.registerWaiting(252L, MatchType.RATED, 1_000, JOINED_AT);
        repository.registerWaiting(253L, MatchType.RATED, 1_100, JOINED_AT);
        ReservedMatchPair reservation = repository.reservePair(252L, 253L, MatchType.RATED).orElseThrow();

        assertThat(repository.confirmMatch(reservation, 1001L)).isTrue();
        assertThat(repository.quarantineReservation(reservation.reservationToken(), JOINED_AT)).isFalse();
        assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":reservation:invalid", reservation.reservationToken()))
                .isNull();
    }

    @Test
    void doesNotQuarantineReservationWhileCreationOrRecoveryLeaseIsActive() {
        repository.registerWaiting(248L, MatchType.RATED, 1_000, JOINED_AT);
        repository.registerWaiting(249L, MatchType.RATED, 1_100, JOINED_AT);
        ReservedMatchPair creationReservation = repository.reservePair(248L, 249L, MatchType.RATED).orElseThrow();
        redisTemplate.opsForZSet().add(reservationExpirationKey(), creationReservation.reservationToken(),
                JOINED_AT.minusSeconds(1).toEpochMilli());
        assertThat(repository.beginMatchCreation(creationReservation)).isTrue();

        assertThat(repository.quarantineReservation(creationReservation.reservationToken(), JOINED_AT)).isFalse();
        assertThat(redisTemplate.opsForZSet().score(reservationExpirationKey(), creationReservation.reservationToken()))
                .isNotNull();

        assertThat(repository.releaseReservation(creationReservation)).isTrue();
        repository.registerWaiting(250L, MatchType.UNRATED, 1_000, JOINED_AT);
        repository.registerWaiting(251L, MatchType.UNRATED, 1_100, JOINED_AT);
        ReservedMatchPair recoveryReservation = repository.reservePair(250L, 251L, MatchType.UNRATED).orElseThrow();
        redisTemplate.opsForZSet().add(reservationExpirationKey(), recoveryReservation.reservationToken(),
                JOINED_AT.minusSeconds(1).toEpochMilli());
        assertThat(repository.claimExpiredReservation(recoveryReservation.reservationToken(), "recovery", JOINED_AT)).isTrue();

        assertThat(repository.quarantineReservation(recoveryReservation.reservationToken(), JOINED_AT)).isFalse();
        assertThat(redisTemplate.opsForZSet().score(reservationExpirationKey(), recoveryReservation.reservationToken()))
                .isNotNull();
    }

    @Test
    void reservationFailureKeepsBothUsersInTheirOriginalWaitingQueues() {
        repository.registerWaiting(231L, MatchType.RATED, 1_000, JOINED_AT);
        repository.registerWaiting(232L, MatchType.UNRATED, 1_000, JOINED_AT);

        Optional<ReservedMatchPair> reservation = repository.reservePair(231L, 232L, MatchType.RATED);

        assertThat(reservation).isEmpty();
        assertThat(repository.findState(231L).status()).isEqualTo(MatchmakingStatus.WAITING);
        assertThat(repository.findState(232L).status()).isEqualTo(MatchmakingStatus.WAITING);
        assertThat(redisTemplate.opsForZSet().score(KEY_PREFIX + ":queue:rated", "231")).isEqualTo(1_000D);
        assertThat(redisTemplate.opsForSet().isMember(KEY_PREFIX + ":queue:unrated", "232")).isTrue();
    }

    @Test
    void concurrentReservationsCannotReserveTheSameUserTwice() throws Exception {
        repository.registerWaiting(241L, MatchType.RATED, 1_000, JOINED_AT);
        repository.registerWaiting(242L, MatchType.RATED, 1_000, JOINED_AT);
        repository.registerWaiting(243L, MatchType.RATED, 1_000, JOINED_AT);
        CyclicBarrier barrier = new CyclicBarrier(2);

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Optional<ReservedMatchPair>>> tasks = List.of(
                    () -> reserveAfterBarrier(barrier, 241L, 242L),
                    () -> reserveAfterBarrier(barrier, 243L, 242L));

            List<Optional<ReservedMatchPair>> reservations = executor.invokeAll(tasks).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .toList();

            assertThat(reservations).filteredOn(Optional::isPresent).hasSize(1);
            assertThat(repository.removeWaiting(242L)).isEqualTo(QueueRemovalResult.RESERVED);
            assertThat(redisTemplate.opsForZSet().zCard(KEY_PREFIX + ":queue:rated")).isEqualTo(1L);
        }
    }

    @Test
    void cancellationAndReservationLeaveEitherAReservationOrOneCancelledUser() throws Exception {
        repository.registerWaiting(251L, MatchType.UNRATED, 1_000, JOINED_AT);
        repository.registerWaiting(252L, MatchType.UNRATED, 1_000, JOINED_AT);
        CyclicBarrier barrier = new CyclicBarrier(2);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var reservationFuture = executor.submit(() -> {
                barrier.await();
                return repository.reservePair(251L, 252L, MatchType.UNRATED);
            });
            var cancellationFuture = executor.submit(() -> {
                barrier.await();
                return repository.removeWaiting(252L);
            });

            Optional<ReservedMatchPair> reservation = reservationFuture.get();
            QueueRemovalResult cancellation = cancellationFuture.get();
            if (reservation.isPresent()) {
                assertThat(cancellation).isEqualTo(QueueRemovalResult.RESERVED);
                assertThat(redisTemplate.opsForSet().members(KEY_PREFIX + ":queue:unrated")).isEmpty();
            } else {
                assertThat(cancellation).isEqualTo(QueueRemovalResult.REMOVED);
                assertThat(repository.findState(251L).status()).isEqualTo(MatchmakingStatus.WAITING);
                assertThat(redisTemplate.opsForSet().isMember(KEY_PREFIX + ":queue:unrated", "251")).isTrue();
            }
        }
    }

    private Optional<ReservedMatchPair> reserveAfterBarrier(CyclicBarrier barrier, long firstUserId, long secondUserId)
            throws Exception {
        barrier.await();
        return repository.reservePair(firstUserId, secondUserId, MatchType.RATED);
    }

    private void assertReservationMetadataIsRemoved(ReservedMatchPair reservation) {
        assertThat(redisTemplate.hasKey(reservationDataKey(reservation))).isFalse();
        assertThat(redisTemplate.opsForZSet().score(reservationExpirationKey(), reservation.reservationToken())).isNull();
        assertThat(redisTemplate.hasKey(KEY_PREFIX + ":reservation:creation:" + reservation.reservationToken())).isFalse();
    }

    private String reservationDataKey(ReservedMatchPair reservation) {
        return KEY_PREFIX + ":reservation:data:" + reservation.reservationToken();
    }

    private String reservationExpirationKey() {
        return KEY_PREFIX + ":reservation:expires";
    }

    private String reservationKey(long userId) {
        return KEY_PREFIX + ":reservation:" + userId;
    }

    private void clearTestKeys() {
        var keys = redisTemplate.keys(KEY_PREFIX + ":*");
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
