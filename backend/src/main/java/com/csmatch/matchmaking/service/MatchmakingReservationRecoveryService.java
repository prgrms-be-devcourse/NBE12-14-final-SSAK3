package com.csmatch.matchmaking.service;

import com.csmatch.match.dto.ActiveMatch;
import com.csmatch.match.dto.ActiveMatchPair;
import com.csmatch.match.service.MatchCreationService;
import com.csmatch.matchmaking.dto.ReservationRecoveryData;
import com.csmatch.matchmaking.entity.ReservationRecoveryResult;
import com.csmatch.matchmaking.repository.MatchmakingRedisRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class MatchmakingReservationRecoveryService {
    private final MatchmakingRedisRepository matchmakingRedisRepository;
    private final MatchCreationService matchCreationService;

    public ReservationRecoveryResult recoverExpiredReservation(String reservationToken, Instant now) {
        if (reservationToken == null || reservationToken.isBlank() || now == null) {
            throw new IllegalArgumentException("Reservation token and current time are required");
        }

        String recoveryExecutionId = UUID.randomUUID().toString();
        if (!matchmakingRedisRepository.claimExpiredReservation(reservationToken, recoveryExecutionId, now)) {
            return ReservationRecoveryResult.NOT_RECOVERABLE;
        }

        try {
            Optional<ReservationRecoveryData> reservation = matchmakingRedisRepository
                    .findReservationRecoveryData(reservationToken);
            if (reservation.isEmpty()) {
                return ReservationRecoveryResult.STALE_RECOVERY;
            }

            ReservationRecoveryData data = reservation.get();
            ActiveMatchPair activeMatches = matchCreationService.findActiveMatchesWithUserLocks(
                    data.firstUserId(), data.secondUserId());
            boolean resolved = matchmakingRedisRepository.resolveExpiredReservation(
                    data,
                    recoveryExecutionId,
                    activeMatches.first().map(ActiveMatch::matchId).orElse(null),
                    activeMatches.first().map(ActiveMatch::matchType).orElse(null),
                    activeMatches.second().map(ActiveMatch::matchId).orElse(null),
                    activeMatches.second().map(ActiveMatch::matchType).orElse(null));
            return resolved ? ReservationRecoveryResult.RECOVERED : ReservationRecoveryResult.STALE_RECOVERY;
        } catch (IllegalArgumentException exception) {
            log.error("Matchmaking reservation recovery metadata is invalid. reservationToken={}", reservationToken,
                    exception);
            return ReservationRecoveryResult.INVALID_METADATA;
        } finally {
            matchmakingRedisRepository.releaseRecoveryClaim(reservationToken, recoveryExecutionId);
        }
    }
}
