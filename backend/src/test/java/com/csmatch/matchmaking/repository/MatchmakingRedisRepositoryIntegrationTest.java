package com.csmatch.matchmaking.repository;

import com.csmatch.matchmaking.entity.MatchType;
import com.csmatch.matchmaking.entity.MatchmakingStatus;
import com.csmatch.matchmaking.entity.QueueRegistrationResult;
import com.csmatch.matchmaking.entity.QueueRemovalResult;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class MatchmakingRedisRepositoryIntegrationTest {
    private static final String KEY_PREFIX = "test:csmatch:matchmaking";
    private static final Instant JOINED_AT = Instant.parse("2026-11-12T00:00:00Z");

    @Autowired
    private StringRedisTemplate redisTemplate;

    private MatchmakingRedisRepository repository;

    @BeforeEach
    void setUp() {
        repository = new MatchmakingRedisRepository(redisTemplate, KEY_PREFIX);
        clearTestKeys();
    }

    @AfterEach
    void tearDown() {
        clearTestKeys();
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

    private void clearTestKeys() {
        var keys = redisTemplate.keys(KEY_PREFIX + ":*");
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
