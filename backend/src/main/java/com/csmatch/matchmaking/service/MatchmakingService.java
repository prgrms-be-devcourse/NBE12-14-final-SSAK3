package com.csmatch.matchmaking.service;

import com.csmatch.global.exception.BusinessException;
import com.csmatch.global.exception.ErrorCode;
import com.csmatch.match.dto.ActiveMatch;
import com.csmatch.match.service.MatchCreationService;
import com.csmatch.matchmaking.dto.ConfirmedMatchResult;
import com.csmatch.matchmaking.dto.MatchCandidate;
import com.csmatch.matchmaking.dto.MatchCreationResult;
import com.csmatch.matchmaking.dto.MatchmakingState;
import com.csmatch.matchmaking.dto.ReservedMatchPair;
import com.csmatch.matchmaking.entity.MatchType;
import com.csmatch.matchmaking.entity.MatchmakingStatus;
import com.csmatch.matchmaking.repository.MatchmakingRedisRepository;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class MatchmakingService {
    private static final int CONFIRM_ATTEMPTS = 2;

    private final MatchmakingRedisRepository matchmakingRedisRepository;
    private final MatchCreationService matchCreationService;

    /**
     * 후보 탐색, Redis 예약, PREPARING 경기 생성, Redis MATCHED 확정을 순서대로 처리한다.
     */
    public Optional<ConfirmedMatchResult> tryCreateAndConfirmMatch(long requesterId, Instant now) {
        Optional<MatchCreationResult> creation = tryCreatePreparingMatch(requesterId, now);
        if (creation.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(confirmCreatedMatch(creation.get()));
    }

    private Optional<ReservedMatchPair> tryMatch(long requesterId, Instant now) {
        if (requesterId <= 0 || now == null) {
            throw new IllegalArgumentException("Positive requester ID and current time are required");
        }

        MatchmakingState requester = matchmakingRedisRepository.findState(requesterId);
        if (requester.status() != MatchmakingStatus.WAITING) {
            return Optional.empty();
        }

        return switch (requester.matchType()) {
            case RATED -> reserveFirstAvailable(requesterId, requester.matchType(),
                    matchmakingRedisRepository.findRatedCandidates(requesterId, now));
            case UNRATED -> reserveFirstAvailable(requesterId, requester.matchType(),
                    matchmakingRedisRepository.findUnratedCandidates(requesterId));
        };
    }

    /**
     * 후보 예약 뒤 독립된 MySQL 트랜잭션으로 PREPARING 경기를 생성한다.
     * Redis MATCHED 확정은 다음 단계에서 처리한다.
     */
    private Optional<MatchCreationResult> tryCreatePreparingMatch(long requesterId, Instant now) {
        Optional<ReservedMatchPair> reservation = tryMatch(requesterId, now);
        if (reservation.isEmpty()) {
            return Optional.empty();
        }

        ReservedMatchPair reservedPair = reservation.get();
        try {
            var match = matchCreationService.createPreparingMatch(
                    reservedPair.matchType(), reservedPair.firstUserId(), reservedPair.secondUserId());
            return Optional.of(new MatchCreationResult(reservedPair, match.getMatchId()));
        } catch (BusinessException exception) {
            if (exception.getErrorCode() == ErrorCode.MATCH_IN_PROGRESS) {
                reconcileActiveMatches(reservedPair, exception);
            } else {
                releaseAfterConfirmedRollback(reservedPair);
            }
            throw exception;
        } catch (IllegalArgumentException exception) {
            releaseAfterConfirmedRollback(reservedPair);
            throw exception;
        } catch (DataAccessException exception) {
            log.error("MySQL access failed after matchmaking reservation; reservation is retained for recovery. "
                    + "firstUserId={}, secondUserId={}", reservedPair.firstUserId(), reservedPair.secondUserId(), exception);
            throw exception;
        } catch (RuntimeException exception) {
            log.error("MySQL transaction outcome is uncertain after matchmaking reservation; reservation is retained. "
                    + "firstUserId={}, secondUserId={}", reservedPair.firstUserId(), reservedPair.secondUserId(), exception);
            throw exception;
        }
    }

    private ConfirmedMatchResult confirmCreatedMatch(MatchCreationResult creation) {
        for (int attempt = 1; attempt <= CONFIRM_ATTEMPTS; attempt++) {
            try {
                if (matchmakingRedisRepository.confirmMatch(creation.reservation(), creation.matchId())) {
                    ReservedMatchPair reservation = creation.reservation();
                    return new ConfirmedMatchResult(creation.matchId(), reservation.matchType(),
                            reservation.firstUserId(), reservation.secondUserId());
                }
                log.error("Redis confirmation was rejected after committed match creation; reservation is retained. "
                                + "matchId={}, firstUserId={}, secondUserId={}",
                        creation.matchId(), creation.reservation().firstUserId(),
                        creation.reservation().secondUserId());
                throw new IllegalStateException("Committed match could not be confirmed in Redis");
            } catch (RedisConnectionFailureException exception) {
                if (attempt == CONFIRM_ATTEMPTS) {
                    log.error("Redis confirmation failed after committed match creation; reservation is retained. "
                                    + "matchId={}, firstUserId={}, secondUserId={}",
                            creation.matchId(), creation.reservation().firstUserId(),
                            creation.reservation().secondUserId(), exception);
                    throw exception;
                }
                log.warn("Retrying Redis confirmation after connection failure. matchId={}, attempt={}",
                        creation.matchId(), attempt);
            }
        }
        throw new IllegalStateException("Unreachable Redis confirmation state");
    }

    private Optional<ReservedMatchPair> reserveFirstAvailable(
            long requesterId,
            MatchType matchType,
            Iterable<MatchCandidate> candidates
    ) {
        for (MatchCandidate candidate : candidates) {
            Optional<ReservedMatchPair> reservation = matchmakingRedisRepository.reservePair(
                    requesterId, candidate.userId(), matchType);
            if (reservation.isPresent()) {
                return reservation;
            }
        }
        return Optional.empty();
    }

    private void releaseAfterConfirmedRollback(ReservedMatchPair reservation) {
        try {
            if (!matchmakingRedisRepository.releaseReservation(reservation)) {
                log.error("Failed to release matchmaking reservation after confirmed MySQL rollback. "
                        + "firstUserId={}, secondUserId={}", reservation.firstUserId(), reservation.secondUserId());
            }
        } catch (RuntimeException exception) {
            log.error("Failed to release matchmaking reservation after confirmed MySQL rollback. "
                    + "firstUserId={}, secondUserId={}", reservation.firstUserId(), reservation.secondUserId(), exception);
        }
    }

    private void reconcileActiveMatches(ReservedMatchPair reservation, BusinessException originalException) {
        try {
            Optional<ActiveMatch> firstActiveMatch = matchCreationService.findActiveMatch(reservation.firstUserId());
            Optional<ActiveMatch> secondActiveMatch = matchCreationService.findActiveMatch(reservation.secondUserId());
            if (firstActiveMatch.isEmpty() && secondActiveMatch.isEmpty()) {
                log.error("MATCH_IN_PROGRESS was raised but no active match was found; reservation is retained. "
                                + "firstUserId={}, secondUserId={}",
                        reservation.firstUserId(), reservation.secondUserId());
                return;
            }

            ActiveMatch first = firstActiveMatch.orElse(null);
            ActiveMatch second = secondActiveMatch.orElse(null);
            if (!matchmakingRedisRepository.resolveReservationAfterActiveMatches(
                    reservation,
                    first == null ? null : first.matchId(), first == null ? null : first.matchType(),
                    second == null ? null : second.matchId(), second == null ? null : second.matchType())) {
                log.error("Failed to reconcile reservation after MATCH_IN_PROGRESS; reservation is retained. "
                                + "firstUserId={}, secondUserId={}",
                        reservation.firstUserId(), reservation.secondUserId());
            }
        } catch (RuntimeException exception) {
            log.error("Failed to reconcile reservation after MATCH_IN_PROGRESS; reservation is retained. "
                            + "firstUserId={}, secondUserId={}",
                    reservation.firstUserId(), reservation.secondUserId(), exception);
            originalException.addSuppressed(exception);
        }
    }
}
