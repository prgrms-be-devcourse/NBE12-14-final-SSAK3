package com.csmatch.matchmaking.service;

import com.csmatch.matchmaking.entity.ReservationRecoveryResult;
import com.csmatch.matchmaking.repository.MatchmakingRedisRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@Slf4j
@RequiredArgsConstructor
public class MatchmakingReservationRecoveryScheduler {
    private static final int BATCH_SIZE = 50;
    private static final Duration INFRASTRUCTURE_FAILURE_COOLDOWN = Duration.ofSeconds(60);

    private final MatchmakingRedisRepository matchmakingRedisRepository;
    private final MatchmakingReservationRecoveryService recoveryService;
    private Instant infrastructureRetryAfter = Instant.MIN;

    @Scheduled(fixedDelay = 10_000)
    public void recoverExpiredReservations() {
        recoverExpiredReservations(Instant.now());
    }

    public void recoverExpiredReservations(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("Current time is required");
        }
        if (now.isBefore(infrastructureRetryAfter)) {
            return;
        }

        final List<String> tokens;
        try {
            tokens = matchmakingRedisRepository.findExpiredReservationTokens(now, BATCH_SIZE);
        } catch (RedisConnectionFailureException exception) {
            deferAfterInfrastructureFailure(now, "Redis", exception);
            return;
        } catch (DataAccessException exception) {
            deferAfterInfrastructureFailure(now, "Redis", exception);
            return;
        }

        for (String reservationToken : tokens) {
            try {
                ReservationRecoveryResult result = recoveryService.recoverExpiredReservation(reservationToken, now);
                if (result == ReservationRecoveryResult.INVALID_METADATA
                        || (result == ReservationRecoveryResult.NOT_RECOVERABLE
                        && !matchmakingRedisRepository.hasReservationRecoveryData(reservationToken))) {
                    matchmakingRedisRepository.quarantineReservation(reservationToken, now);
                }
            } catch (RedisConnectionFailureException exception) {
                deferAfterInfrastructureFailure(now, "Redis", exception);
                return;
            } catch (DataAccessException exception) {
                deferAfterInfrastructureFailure(now, "database", exception);
                return;
            } catch (RuntimeException exception) {
                log.error("Failed to recover expired matchmaking reservation. reservationToken={}", reservationToken,
                        exception);
            }
        }
        infrastructureRetryAfter = Instant.MIN;
    }

    private void deferAfterInfrastructureFailure(Instant now, String dependency, RuntimeException exception) {
        infrastructureRetryAfter = now.plus(INFRASTRUCTURE_FAILURE_COOLDOWN);
        log.error("Matchmaking reservation recovery is deferred after {} failure. retryAfter={}", dependency,
                infrastructureRetryAfter, exception);
    }
}
