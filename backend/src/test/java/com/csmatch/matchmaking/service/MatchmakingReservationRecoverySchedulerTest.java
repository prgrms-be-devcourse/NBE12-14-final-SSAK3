package com.csmatch.matchmaking.service;

import com.csmatch.matchmaking.entity.ReservationRecoveryResult;
import com.csmatch.matchmaking.repository.MatchmakingRedisRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchmakingReservationRecoverySchedulerTest {
    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");

    @Mock
    private MatchmakingRedisRepository matchmakingRedisRepository;
    @Mock
    private MatchmakingReservationRecoveryService recoveryService;

    @Test
    void continuesWithLaterTokensWhenOneRecoveryFails() {
        MatchmakingReservationRecoveryScheduler scheduler = new MatchmakingReservationRecoveryScheduler(
                matchmakingRedisRepository, recoveryService);
        when(matchmakingRedisRepository.findExpiredReservationTokens(NOW, 50)).thenReturn(List.of("first", "second"));
        when(recoveryService.recoverExpiredReservation("first", NOW)).thenThrow(new IllegalStateException("bad token"));
        when(recoveryService.recoverExpiredReservation("second", NOW)).thenReturn(ReservationRecoveryResult.RECOVERED);

        scheduler.recoverExpiredReservations(NOW);

        verify(recoveryService).recoverExpiredReservation("first", NOW);
        verify(recoveryService).recoverExpiredReservation("second", NOW);
    }

    @Test
    void defersNewBatchAfterInfrastructureFailureInsteadOfRetryingEveryToken() {
        MatchmakingReservationRecoveryScheduler scheduler = new MatchmakingReservationRecoveryScheduler(
                matchmakingRedisRepository, recoveryService);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("database unavailable");
        when(matchmakingRedisRepository.findExpiredReservationTokens(NOW, 50)).thenReturn(List.of("first", "second"));
        when(recoveryService.recoverExpiredReservation("first", NOW)).thenThrow(failure);

        scheduler.recoverExpiredReservations(NOW);
        scheduler.recoverExpiredReservations(NOW.plusSeconds(10));

        verify(recoveryService).recoverExpiredReservation("first", NOW);
        verify(recoveryService, never()).recoverExpiredReservation(eq("second"), any());
        verify(matchmakingRedisRepository, never()).findExpiredReservationTokens(NOW.plusSeconds(10), 50);
    }
}
