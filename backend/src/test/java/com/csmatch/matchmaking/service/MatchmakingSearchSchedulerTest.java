package com.csmatch.matchmaking.service;

import com.csmatch.global.exception.BusinessException;
import com.csmatch.global.exception.ErrorCode;
import com.csmatch.matchmaking.dto.ConfirmedMatchResult;
import com.csmatch.matchmaking.entity.MatchType;
import com.csmatch.matchmaking.repository.MatchmakingRedisRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchmakingSearchSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");

    @Mock
    private MatchmakingRedisRepository matchmakingRedisRepository;
    @Mock
    private MatchmakingService matchmakingService;
    @InjectMocks
    private MatchmakingSearchScheduler scheduler;

    @Test
    void continuesSearchingOtherUsersAfterAnIndividualFailureAndDeduplicatesSnapshotIds() {
        when(matchmakingRedisRepository.findRatedWaitingUserIds()).thenReturn(List.of(101L, 101L, 102L, 103L));
        when(matchmakingService.tryCreateAndConfirmMatch(101L, NOW))
                .thenThrow(new BusinessException(ErrorCode.MATCH_IN_PROGRESS));
        when(matchmakingService.tryCreateAndConfirmMatch(102L, NOW)).thenReturn(Optional.empty());
        when(matchmakingService.tryCreateAndConfirmMatch(103L, NOW))
                .thenReturn(Optional.of(new ConfirmedMatchResult(55L, MatchType.RATED, 103L, 104L)));

        scheduler.rematchRatedWaiters(NOW);

        verify(matchmakingService, times(1)).tryCreateAndConfirmMatch(101L, NOW);
        verify(matchmakingService).tryCreateAndConfirmMatch(102L, NOW);
        verify(matchmakingService).tryCreateAndConfirmMatch(103L, NOW);
    }
}
