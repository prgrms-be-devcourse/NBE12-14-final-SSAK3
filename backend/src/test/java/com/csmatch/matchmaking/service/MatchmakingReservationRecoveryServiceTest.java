package com.csmatch.matchmaking.service;

import com.csmatch.match.service.MatchCreationService;
import com.csmatch.matchmaking.dto.ReservationRecoveryData;
import com.csmatch.matchmaking.entity.MatchType;
import com.csmatch.matchmaking.entity.ReservationRecoveryResult;
import com.csmatch.matchmaking.repository.MatchmakingRedisRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchmakingReservationRecoveryServiceTest {
    private static final String TOKEN = "reservation-token";
    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");

    @Mock
    private MatchmakingRedisRepository matchmakingRedisRepository;
    @Mock
    private MatchCreationService matchCreationService;

    @Test
    void retainsRedisReservationWhenMySqlLookupFails() {
        MatchmakingReservationRecoveryService service = new MatchmakingReservationRecoveryService(
                matchmakingRedisRepository, matchCreationService);
        ReservationRecoveryData data = recoveryData();
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("MySQL unavailable");
        when(matchmakingRedisRepository.claimExpiredReservation(ArgumentMatchers.eq(TOKEN),
                ArgumentMatchers.anyString(), ArgumentMatchers.eq(NOW))).thenReturn(true);
        when(matchmakingRedisRepository.findReservationRecoveryData(TOKEN)).thenReturn(Optional.of(data));
        when(matchCreationService.findActiveMatchesWithUserLocks(101L, 102L)).thenThrow(failure);

        assertThatThrownBy(() -> service.recoverExpiredReservation(TOKEN, NOW)).isSameAs(failure);

        verify(matchmakingRedisRepository, never()).resolveExpiredReservation(
                ArgumentMatchers.any(), ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
        verify(matchmakingRedisRepository).releaseRecoveryClaim(ArgumentMatchers.eq(TOKEN), ArgumentMatchers.anyString());
    }

    @Test
    void preservesIncompleteMetadataForManualInspectionAndRetry() {
        MatchmakingReservationRecoveryService service = new MatchmakingReservationRecoveryService(
                matchmakingRedisRepository, matchCreationService);
        when(matchmakingRedisRepository.claimExpiredReservation(ArgumentMatchers.eq(TOKEN),
                ArgumentMatchers.anyString(), ArgumentMatchers.eq(NOW))).thenReturn(true);
        when(matchmakingRedisRepository.findReservationRecoveryData(TOKEN))
                .thenThrow(new IllegalArgumentException("incomplete metadata"));

        assertThat(service.recoverExpiredReservation(TOKEN, NOW))
                .isEqualTo(ReservationRecoveryResult.INVALID_METADATA);

        verify(matchmakingRedisRepository, never()).resolveExpiredReservation(
                ArgumentMatchers.any(), ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
        verify(matchmakingRedisRepository).releaseRecoveryClaim(ArgumentMatchers.eq(TOKEN), ArgumentMatchers.anyString());
    }

    private ReservationRecoveryData recoveryData() {
        return new ReservationRecoveryData(TOKEN, 101L, 102L, MatchType.RATED, 1_000, 1_100,
                NOW.minusSeconds(10), NOW.minusSeconds(5), NOW.minusSeconds(1));
    }
}
