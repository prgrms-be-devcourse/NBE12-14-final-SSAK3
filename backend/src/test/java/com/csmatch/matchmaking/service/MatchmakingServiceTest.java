package com.csmatch.matchmaking.service;

import com.csmatch.global.exception.BusinessException;
import com.csmatch.global.exception.ErrorCode;
import com.csmatch.match.dto.ActiveMatch;
import com.csmatch.match.entity.Match;
import com.csmatch.match.service.MatchCreationService;
import com.csmatch.matchmaking.dto.ConfirmedMatchResult;
import com.csmatch.matchmaking.dto.MatchCandidate;
import com.csmatch.matchmaking.dto.MatchmakingState;
import com.csmatch.matchmaking.dto.ReservedMatchPair;
import com.csmatch.matchmaking.entity.MatchType;
import com.csmatch.matchmaking.entity.MatchmakingStatus;
import com.csmatch.matchmaking.repository.MatchmakingRedisRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchmakingServiceTest {
    private static final long REQUESTER_ID = 101L;
    private static final long CANDIDATE_ID = 102L;
    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");

    @Mock
    private MatchmakingRedisRepository matchmakingRedisRepository;
    @Mock
    private MatchCreationService matchCreationService;
    @InjectMocks
    private MatchmakingService matchmakingService;

    @Test
    void createsAndConfirmsPreparingMatch() {
        ReservedMatchPair reservation = reserveRatedCandidate();
        Match match = org.mockito.Mockito.mock(Match.class);
        when(matchCreationService.createPreparingMatch(MatchType.RATED, REQUESTER_ID, CANDIDATE_ID)).thenReturn(match);
        when(match.getMatchId()).thenReturn(77L);
        when(matchmakingRedisRepository.confirmMatch(reservation, 77L)).thenReturn(true);

        Optional<ConfirmedMatchResult> result = matchmakingService.tryCreateAndConfirmMatch(REQUESTER_ID, NOW);

        assertThat(result).contains(new ConfirmedMatchResult(77L, MatchType.RATED, REQUESTER_ID, CANDIDATE_ID));
        verify(matchmakingRedisRepository).confirmMatch(reservation, 77L);
        verify(matchmakingRedisRepository, never()).releaseReservation(reservation);
    }

    @Test
    void returnsEmptyWhenNoCandidateCanBeReserved() {
        when(matchmakingRedisRepository.findState(REQUESTER_ID)).thenReturn(waitingState(MatchType.RATED));
        when(matchmakingRedisRepository.findRatedCandidates(REQUESTER_ID, NOW)).thenReturn(List.of());

        assertThat(matchmakingService.tryCreateAndConfirmMatch(REQUESTER_ID, NOW)).isEmpty();
        verify(matchCreationService, never()).createPreparingMatch(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void doesNotCreateMatchWhenRecoveryHasInvalidatedTheReservation() {
        ReservedMatchPair reservation = reserveRatedCandidate();
        when(matchmakingRedisRepository.beginMatchCreation(reservation)).thenReturn(false);

        assertThat(matchmakingService.tryCreateAndConfirmMatch(REQUESTER_ID, NOW)).isEmpty();

        verify(matchCreationService, never()).createPreparingMatch(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void doesNotReleaseOrCreateAnotherMatchWhenConfirmationIsRejected() {
        ReservedMatchPair reservation = reserveRatedCandidate();
        Match match = org.mockito.Mockito.mock(Match.class);
        when(matchCreationService.createPreparingMatch(MatchType.RATED, REQUESTER_ID, CANDIDATE_ID)).thenReturn(match);
        when(match.getMatchId()).thenReturn(77L);
        when(matchmakingRedisRepository.confirmMatch(reservation, 77L)).thenReturn(false);

        assertThatThrownBy(() -> matchmakingService.tryCreateAndConfirmMatch(REQUESTER_ID, NOW))
                .isInstanceOf(IllegalStateException.class);
        verify(matchCreationService, times(1)).createPreparingMatch(MatchType.RATED, REQUESTER_ID, CANDIDATE_ID);
        verify(matchmakingRedisRepository, never()).releaseReservation(reservation);
    }

    @Test
    void retriesRedisConnectionFailureWithoutCreatingAnotherMatch() {
        ReservedMatchPair reservation = reserveRatedCandidate();
        Match match = org.mockito.Mockito.mock(Match.class);
        RedisConnectionFailureException failure = new RedisConnectionFailureException("Redis unavailable");
        when(matchCreationService.createPreparingMatch(MatchType.RATED, REQUESTER_ID, CANDIDATE_ID)).thenReturn(match);
        when(match.getMatchId()).thenReturn(77L);
        when(matchmakingRedisRepository.confirmMatch(reservation, 77L)).thenThrow(failure).thenReturn(true);

        assertThat(matchmakingService.tryCreateAndConfirmMatch(REQUESTER_ID, NOW)).isPresent();
        verify(matchCreationService, times(1)).createPreparingMatch(MatchType.RATED, REQUESTER_ID, CANDIDATE_ID);
        verify(matchmakingRedisRepository, times(2)).confirmMatch(reservation, 77L);
        verify(matchmakingRedisRepository, never()).releaseReservation(reservation);
    }

    @Test
    void retainsReservationAfterAllRedisConfirmationRetriesFail() {
        ReservedMatchPair reservation = reserveRatedCandidate();
        Match match = org.mockito.Mockito.mock(Match.class);
        RedisConnectionFailureException failure = new RedisConnectionFailureException("Redis unavailable");
        when(matchCreationService.createPreparingMatch(MatchType.RATED, REQUESTER_ID, CANDIDATE_ID)).thenReturn(match);
        when(match.getMatchId()).thenReturn(77L);
        when(matchmakingRedisRepository.confirmMatch(reservation, 77L)).thenThrow(failure);

        assertThatThrownBy(() -> matchmakingService.tryCreateAndConfirmMatch(REQUESTER_ID, NOW)).isSameAs(failure);

        verify(matchCreationService, times(1)).createPreparingMatch(MatchType.RATED, REQUESTER_ID, CANDIDATE_ID);
        verify(matchmakingRedisRepository, times(2)).confirmMatch(reservation, 77L);
        verify(matchmakingRedisRepository, never()).releaseReservation(reservation);
    }

    @Test
    void releasesReservationAfterConfirmedBusinessRollback() {
        ReservedMatchPair reservation = reserveRatedCandidate();
        when(matchCreationService.createPreparingMatch(MatchType.RATED, REQUESTER_ID, CANDIDATE_ID))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND));
        when(matchmakingRedisRepository.releaseReservation(reservation)).thenReturn(true);

        assertThatThrownBy(() -> matchmakingService.tryCreateAndConfirmMatch(REQUESTER_ID, NOW))
                .isInstanceOf(BusinessException.class);
        verify(matchmakingRedisRepository).releaseReservation(reservation);
    }

    @Test
    void reconcilesEachUserWhenMatchIsAlreadyInProgress() {
        ReservedMatchPair reservation = reserveRatedCandidate();
        ActiveMatch activeMatch = new ActiveMatch(55L, MatchType.UNRATED);
        when(matchCreationService.createPreparingMatch(MatchType.RATED, REQUESTER_ID, CANDIDATE_ID))
                .thenThrow(new BusinessException(ErrorCode.MATCH_IN_PROGRESS));
        when(matchCreationService.findActiveMatch(REQUESTER_ID)).thenReturn(Optional.of(activeMatch));
        when(matchCreationService.findActiveMatch(CANDIDATE_ID)).thenReturn(Optional.empty());
        when(matchmakingRedisRepository.resolveReservationAfterActiveMatches(
                reservation, 55L, MatchType.UNRATED, null, null)).thenReturn(true);

        assertThatThrownBy(() -> matchmakingService.tryCreateAndConfirmMatch(REQUESTER_ID, NOW))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.MATCH_IN_PROGRESS);
        verify(matchmakingRedisRepository).resolveReservationAfterActiveMatches(
                reservation, 55L, MatchType.UNRATED, null, null);
        verify(matchmakingRedisRepository, never()).releaseReservation(reservation);
    }

    @Test
    void retainsReservationWhenDatabaseAccessFailsWithUncertainTransactionOutcome() {
        ReservedMatchPair reservation = reserveRatedCandidate();
        DataAccessResourceFailureException databaseFailure = new DataAccessResourceFailureException("database unavailable");
        when(matchCreationService.createPreparingMatch(MatchType.RATED, REQUESTER_ID, CANDIDATE_ID))
                .thenThrow(databaseFailure);

        assertThatThrownBy(() -> matchmakingService.tryCreateAndConfirmMatch(REQUESTER_ID, NOW))
                .isSameAs(databaseFailure);
        verify(matchmakingRedisRepository, never()).releaseReservation(reservation);
    }

    private ReservedMatchPair reserveRatedCandidate() {
        MatchCandidate candidate = new MatchCandidate(CANDIDATE_ID, 1_050, NOW.minusSeconds(20));
        ReservedMatchPair reservation = new ReservedMatchPair(REQUESTER_ID, CANDIDATE_ID, MatchType.RATED, "token");
        when(matchmakingRedisRepository.findState(REQUESTER_ID)).thenReturn(waitingState(MatchType.RATED));
        when(matchmakingRedisRepository.findRatedCandidates(REQUESTER_ID, NOW)).thenReturn(List.of(candidate));
        when(matchmakingRedisRepository.reservePair(REQUESTER_ID, CANDIDATE_ID, MatchType.RATED))
                .thenReturn(Optional.of(reservation));
        when(matchmakingRedisRepository.beginMatchCreation(reservation)).thenReturn(true);
        return reservation;
    }

    private MatchmakingState waitingState(MatchType matchType) {
        return new MatchmakingState(MatchmakingStatus.WAITING, matchType, 1_000, NOW.minusSeconds(10), null);
    }
}
