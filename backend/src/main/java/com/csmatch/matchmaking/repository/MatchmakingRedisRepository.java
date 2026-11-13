package com.csmatch.matchmaking.repository;

import com.csmatch.matchmaking.entity.MatchType;
import com.csmatch.matchmaking.dto.MatchCandidate;
import com.csmatch.matchmaking.dto.MatchmakingState;
import com.csmatch.matchmaking.dto.ReservedMatchPair;
import com.csmatch.matchmaking.entity.MatchmakingStatus;
import com.csmatch.matchmaking.entity.QueueRegistrationResult;
import com.csmatch.matchmaking.entity.QueueRemovalResult;
import com.csmatch.matchmaking.service.RatingRangeCalculator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class MatchmakingRedisRepository {
    private static final String DEFAULT_KEY_PREFIX = "csmatch:matchmaking";
    private static final DefaultRedisScript<Long> REGISTER_WAITING_SCRIPT = new DefaultRedisScript<>("""
            local existingStatus = redis.call('HGET', KEYS[1], 'status')
            if existingStatus then
                if existingStatus == 'WAITING' then
                    return 0
                end
                return 1
            end

            redis.call('HSET', KEYS[1],
                'status', 'WAITING',
                'matchType', ARGV[1],
                'rating', ARGV[2],
                'joinedAtEpochMillis', ARGV[3])

            if ARGV[1] == 'RATED' then
                redis.call('ZADD', KEYS[2], ARGV[2], ARGV[4])
            else
                redis.call('SADD', KEYS[3], ARGV[4])
            end
            return 2
            """, Long.class);
    private static final DefaultRedisScript<Long> REMOVE_WAITING_SCRIPT = new DefaultRedisScript<>("""
            local status = redis.call('HGET', KEYS[1], 'status')
            if not status then
                return 0
            end
            if status == 'MATCHED' then
                return 2
            end
            if redis.call('EXISTS', KEYS[4]) == 1 then
                return 3
            end
            if status ~= 'WAITING' then
                return -1
            end

            local matchType = redis.call('HGET', KEYS[1], 'matchType')
            if matchType == 'RATED' then
                redis.call('ZREM', KEYS[2], ARGV[1])
            elseif matchType == 'UNRATED' then
                redis.call('SREM', KEYS[3], ARGV[1])
            else
                return -1
            end
            redis.call('DEL', KEYS[1])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RESERVE_PAIR_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[5]) == 1 or redis.call('EXISTS', KEYS[6]) == 1 then
                return 0
            end

            local firstStatus = redis.call('HGET', KEYS[1], 'status')
            local secondStatus = redis.call('HGET', KEYS[2], 'status')
            local firstType = redis.call('HGET', KEYS[1], 'matchType')
            local secondType = redis.call('HGET', KEYS[2], 'matchType')
            if firstStatus ~= 'WAITING' or secondStatus ~= 'WAITING'
                    or firstType ~= ARGV[1] or secondType ~= ARGV[1] then
                return 0
            end

            if ARGV[1] == 'RATED' then
                if not redis.call('ZSCORE', KEYS[3], ARGV[2])
                        or not redis.call('ZSCORE', KEYS[3], ARGV[3]) then
                    return 0
                end
                redis.call('ZREM', KEYS[3], ARGV[2], ARGV[3])
            elseif ARGV[1] == 'UNRATED' then
                if redis.call('SISMEMBER', KEYS[4], ARGV[2]) == 0
                        or redis.call('SISMEMBER', KEYS[4], ARGV[3]) == 0 then
                    return 0
                end
                redis.call('SREM', KEYS[4], ARGV[2], ARGV[3])
            else
                return 0
            end

            redis.call('SET', KEYS[5], ARGV[4])
            redis.call('SET', KEYS[6], ARGV[4])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE_RESERVATION_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[5]) ~= ARGV[4] or redis.call('GET', KEYS[6]) ~= ARGV[4] then
                return 0
            end

            local firstStatus = redis.call('HGET', KEYS[1], 'status')
            local secondStatus = redis.call('HGET', KEYS[2], 'status')
            local firstType = redis.call('HGET', KEYS[1], 'matchType')
            local secondType = redis.call('HGET', KEYS[2], 'matchType')
            if firstStatus ~= 'WAITING' or secondStatus ~= 'WAITING'
                    or firstType ~= ARGV[1] or secondType ~= ARGV[1] then
                return 0
            end

            if ARGV[1] == 'RATED' then
                redis.call('ZADD', KEYS[3], ARGV[5], ARGV[2])
                redis.call('ZADD', KEYS[3], ARGV[6], ARGV[3])
            elseif ARGV[1] == 'UNRATED' then
                redis.call('SADD', KEYS[4], ARGV[2], ARGV[3])
            else
                return 0
            end
            redis.call('DEL', KEYS[5], KEYS[6])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> CONFIRM_MATCH_SCRIPT = new DefaultRedisScript<>("""
            local firstStatus = redis.call('HGET', KEYS[1], 'status')
            local secondStatus = redis.call('HGET', KEYS[2], 'status')
            local firstType = redis.call('HGET', KEYS[1], 'matchType')
            local secondType = redis.call('HGET', KEYS[2], 'matchType')
            if firstStatus == 'MATCHED' and secondStatus == 'MATCHED' then
                if firstType == ARGV[1] and secondType == ARGV[1]
                        and redis.call('HGET', KEYS[1], 'matchId') == ARGV[5]
                        and redis.call('HGET', KEYS[2], 'matchId') == ARGV[5]
                        and redis.call('HGET', KEYS[1], 'reservationToken') == ARGV[4]
                        and redis.call('HGET', KEYS[2], 'reservationToken') == ARGV[4] then
                    return 1
                end
                return 0
            end

            if redis.call('GET', KEYS[5]) ~= ARGV[4] or redis.call('GET', KEYS[6]) ~= ARGV[4] then
                return 0
            end
            if firstStatus ~= 'WAITING' or secondStatus ~= 'WAITING'
                    or firstType ~= ARGV[1] or secondType ~= ARGV[1] then
                return 0
            end

            if ARGV[1] == 'RATED' then
                redis.call('ZREM', KEYS[3], ARGV[2], ARGV[3])
            elseif ARGV[1] == 'UNRATED' then
                redis.call('SREM', KEYS[4], ARGV[2], ARGV[3])
            else
                return 0
            end

            redis.call('HSET', KEYS[1],
                'status', 'MATCHED',
                'matchType', ARGV[1],
                'matchId', ARGV[5],
                'reservationToken', ARGV[4])
            redis.call('HSET', KEYS[2],
                'status', 'MATCHED',
                'matchType', ARGV[1],
                'matchId', ARGV[5],
                'reservationToken', ARGV[4])
            redis.call('HDEL', KEYS[1], 'rating', 'joinedAtEpochMillis')
            redis.call('HDEL', KEYS[2], 'rating', 'joinedAtEpochMillis')
            redis.call('DEL', KEYS[5], KEYS[6])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RESOLVE_ACTIVE_MATCHES_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[5]) ~= ARGV[4] or redis.call('GET', KEYS[6]) ~= ARGV[4] then
                return 0
            end

            local firstStatus = redis.call('HGET', KEYS[1], 'status')
            local secondStatus = redis.call('HGET', KEYS[2], 'status')
            local firstType = redis.call('HGET', KEYS[1], 'matchType')
            local secondType = redis.call('HGET', KEYS[2], 'matchType')
            if firstStatus ~= 'WAITING' or secondStatus ~= 'WAITING'
                    or firstType ~= ARGV[1] or secondType ~= ARGV[1] then
                return 0
            end
            if ARGV[5] == '' and ARGV[7] == '' then
                return 0
            end

            if ARGV[1] == 'RATED' then
                if (ARGV[5] == '' and not redis.call('HGET', KEYS[1], 'rating'))
                        or (ARGV[7] == '' and not redis.call('HGET', KEYS[2], 'rating')) then
                    return 0
                end
            end

            if ARGV[1] == 'RATED' then
                redis.call('ZREM', KEYS[3], ARGV[2], ARGV[3])
            elseif ARGV[1] == 'UNRATED' then
                redis.call('SREM', KEYS[4], ARGV[2], ARGV[3])
            else
                return 0
            end

            local function resolveUser(stateKey, userId, activeMatchId, activeMatchType)
                if activeMatchId ~= '' then
                    redis.call('HSET', stateKey,
                        'status', 'MATCHED',
                        'matchType', activeMatchType,
                        'matchId', activeMatchId)
                    redis.call('HDEL', stateKey, 'rating', 'joinedAtEpochMillis')
                elseif ARGV[1] == 'RATED' then
                    local rating = redis.call('HGET', stateKey, 'rating')
                    redis.call('ZADD', KEYS[3], rating, userId)
                else
                    redis.call('SADD', KEYS[4], userId)
                end
            end

            resolveUser(KEYS[1], ARGV[2], ARGV[5], ARGV[6])
            resolveUser(KEYS[2], ARGV[3], ARGV[7], ARGV[8])
            redis.call('DEL', KEYS[5], KEYS[6])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;

    @Autowired
    public MatchmakingRedisRepository(StringRedisTemplate redisTemplate) {
        this(redisTemplate, DEFAULT_KEY_PREFIX);
    }

    MatchmakingRedisRepository(StringRedisTemplate redisTemplate, String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix;
    }

    public QueueRegistrationResult registerWaiting(long userId, MatchType matchType, int rating, Instant joinedAt) {
        validateWaitingEntry(userId, matchType, joinedAt);
        Long result = redisTemplate.execute(REGISTER_WAITING_SCRIPT,
                List.of(stateKey(userId), ratedQueueKey(), unratedQueueKey()),
                matchType.name(), Integer.toString(rating), Long.toString(joinedAt.toEpochMilli()), Long.toString(userId));
        return switch (requireResultCode(result)) {
            case 0 -> QueueRegistrationResult.ALREADY_WAITING;
            case 1 -> QueueRegistrationResult.ALREADY_MATCHED;
            case 2 -> QueueRegistrationResult.REGISTERED;
            default -> throw new IllegalStateException("Unexpected Redis registration result");
        };
    }

    public QueueRemovalResult removeWaiting(long userId) {
        validateUserId(userId);
        Long result = redisTemplate.execute(REMOVE_WAITING_SCRIPT,
                List.of(stateKey(userId), ratedQueueKey(), unratedQueueKey(), reservationKey(userId)),
                Long.toString(userId));
        return switch (requireResultCode(result)) {
            case 0 -> QueueRemovalResult.NOT_IN_QUEUE;
            case 1 -> QueueRemovalResult.REMOVED;
            case 2 -> QueueRemovalResult.ALREADY_MATCHED;
            case 3 -> QueueRemovalResult.RESERVED;
            default -> throw new IllegalStateException("Unexpected Redis removal result");
        };
    }

    public List<MatchCandidate> findRatedCandidates(long userId, Instant now) {
        validateUserId(userId);
        if (now == null) {
            throw new IllegalArgumentException("Current time is required");
        }
        Optional<MatchmakingState> requester = waitingState(userId, MatchType.RATED);
        if (requester.isEmpty() || isReserved(userId) || !isInQueue(userId, MatchType.RATED)) {
            return List.of();
        }

        MatchmakingState state = requester.get();
        int requesterRange = RatingRangeCalculator.calculate(state.joinedAt(), now);
        Set<String> userIds = redisTemplate.opsForZSet().rangeByScore(ratedQueueKey(),
                state.rating() - requesterRange, state.rating() + requesterRange);
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }

        List<MatchCandidate> candidates = new ArrayList<>();
        for (String candidateId : userIds) {
            Long parsedUserId = parsePositiveUserId(candidateId);
            if (parsedUserId == null || parsedUserId == userId || isReserved(parsedUserId)) {
                continue;
            }
            waitingState(parsedUserId, MatchType.RATED).ifPresent(candidate -> {
                int candidateRange = RatingRangeCalculator.calculate(candidate.joinedAt(), now);
                if (Math.abs(state.rating() - candidate.rating()) <= candidateRange) {
                    candidates.add(new MatchCandidate(parsedUserId, candidate.rating(), candidate.joinedAt()));
                }
            });
        }
        candidates.sort(Comparator.comparingInt((MatchCandidate candidate) -> Math.abs(state.rating() - candidate.rating()))
                .thenComparing(MatchCandidate::joinedAt)
                .thenComparingLong(MatchCandidate::userId));
        return List.copyOf(candidates);
    }

    public List<Long> findRatedWaitingUserIds() {
        Set<String> userIds = redisTemplate.opsForZSet().range(ratedQueueKey(), 0, -1);
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }

        List<Long> waitingUserIds = new ArrayList<>();
        for (String userId : userIds) {
            Long parsedUserId = parsePositiveUserId(userId);
            if (parsedUserId != null) {
                waitingUserIds.add(parsedUserId);
            }
        }
        return List.copyOf(waitingUserIds);
    }

    public Optional<MatchCandidate> findUnratedCandidate(long userId) {
        return findUnratedCandidates(userId).stream().findFirst();
    }

    public List<MatchCandidate> findUnratedCandidates(long userId) {
        validateUserId(userId);
        if (waitingState(userId, MatchType.UNRATED).isEmpty() || isReserved(userId)
                || !isInQueue(userId, MatchType.UNRATED)) {
            return List.of();
        }

        Set<String> userIds = redisTemplate.opsForSet().members(unratedQueueKey());
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }

        List<MatchCandidate> candidates = new ArrayList<>();
        for (String candidateId : userIds) {
            Long parsedUserId = parsePositiveUserId(candidateId);
            if (parsedUserId == null || parsedUserId == userId || isReserved(parsedUserId)) {
                continue;
            }
            waitingState(parsedUserId, MatchType.UNRATED).ifPresent(candidate ->
                    candidates.add(new MatchCandidate(parsedUserId, candidate.rating(), candidate.joinedAt())));
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        Collections.shuffle(candidates, ThreadLocalRandom.current());
        return List.copyOf(candidates);
    }

    public Optional<ReservedMatchPair> reservePair(long firstUserId, long secondUserId, MatchType matchType) {
        validatePair(firstUserId, secondUserId, matchType);
        String reservationToken = UUID.randomUUID().toString();
        Long result = redisTemplate.execute(RESERVE_PAIR_SCRIPT,
                List.of(stateKey(firstUserId), stateKey(secondUserId), ratedQueueKey(), unratedQueueKey(),
                        reservationKey(firstUserId), reservationKey(secondUserId)),
                matchType.name(), Long.toString(firstUserId), Long.toString(secondUserId), reservationToken);
        int resultCode = requireResultCode(result);
        if (resultCode == 0) {
            return Optional.empty();
        }
        if (resultCode != 1) {
            throw new IllegalStateException("Unexpected Redis reservation result");
        }
        return Optional.of(new ReservedMatchPair(firstUserId, secondUserId, matchType, reservationToken));
    }

    public boolean releaseReservation(ReservedMatchPair reservation) {
        if (reservation == null || reservation.reservationToken() == null || reservation.reservationToken().isBlank()) {
            throw new IllegalArgumentException("Reservation is required");
        }
        validatePair(reservation.firstUserId(), reservation.secondUserId(), reservation.matchType());

        MatchmakingState first = waitingState(reservation.firstUserId(), reservation.matchType())
                .orElse(null);
        MatchmakingState second = waitingState(reservation.secondUserId(), reservation.matchType())
                .orElse(null);
        if (first == null || second == null) {
            return false;
        }
        Long result = redisTemplate.execute(RELEASE_RESERVATION_SCRIPT,
                List.of(stateKey(reservation.firstUserId()), stateKey(reservation.secondUserId()),
                        ratedQueueKey(), unratedQueueKey(), reservationKey(reservation.firstUserId()),
                        reservationKey(reservation.secondUserId())),
                reservation.matchType().name(), Long.toString(reservation.firstUserId()),
                Long.toString(reservation.secondUserId()), reservation.reservationToken(),
                Integer.toString(first.rating()), Integer.toString(second.rating()));
        return requireResultCode(result) == 1;
    }

    public boolean confirmMatch(ReservedMatchPair reservation, long matchId) {
        if (matchId <= 0) {
            throw new IllegalArgumentException("Match ID must be positive");
        }
        if (reservation == null || reservation.reservationToken() == null || reservation.reservationToken().isBlank()) {
            throw new IllegalArgumentException("Reservation is required");
        }
        validatePair(reservation.firstUserId(), reservation.secondUserId(), reservation.matchType());

        Long result = redisTemplate.execute(CONFIRM_MATCH_SCRIPT,
                List.of(stateKey(reservation.firstUserId()), stateKey(reservation.secondUserId()),
                        ratedQueueKey(), unratedQueueKey(), reservationKey(reservation.firstUserId()),
                        reservationKey(reservation.secondUserId())),
                reservation.matchType().name(), Long.toString(reservation.firstUserId()),
                Long.toString(reservation.secondUserId()), reservation.reservationToken(), Long.toString(matchId));
        return requireResultCode(result) == 1;
    }

    public boolean resolveReservationAfterActiveMatches(
            ReservedMatchPair reservation,
            Long firstMatchId,
            MatchType firstMatchType,
            Long secondMatchId,
            MatchType secondMatchType
    ) {
        validateReservation(reservation);
        validateActiveMatch(firstMatchId, firstMatchType);
        validateActiveMatch(secondMatchId, secondMatchType);
        if (firstMatchId == null && secondMatchId == null) {
            throw new IllegalArgumentException("At least one active match is required");
        }

        Long result = redisTemplate.execute(RESOLVE_ACTIVE_MATCHES_SCRIPT,
                List.of(stateKey(reservation.firstUserId()), stateKey(reservation.secondUserId()),
                        ratedQueueKey(), unratedQueueKey(), reservationKey(reservation.firstUserId()),
                        reservationKey(reservation.secondUserId())),
                reservation.matchType().name(), Long.toString(reservation.firstUserId()),
                Long.toString(reservation.secondUserId()), reservation.reservationToken(),
                nullableMatchId(firstMatchId), nullableMatchType(firstMatchType),
                nullableMatchId(secondMatchId), nullableMatchType(secondMatchType));
        return requireResultCode(result) == 1;
    }

    public MatchmakingState findState(long userId) {
        validateUserId(userId);
        Map<Object, Object> values = redisTemplate.opsForHash().entries(stateKey(userId));
        if (values.isEmpty()) {
            return MatchmakingState.idle();
        }

        String statusValue = valueOf(values, "status");
        MatchmakingStatus status = MatchmakingStatus.valueOf(statusValue);
        if (status == MatchmakingStatus.MATCHED) {
            return new MatchmakingState(status,
                    MatchType.valueOf(valueOf(values, "matchType")),
                    null,
                    null,
                    Long.valueOf(valueOf(values, "matchId")));
        }
        if (status == MatchmakingStatus.WAITING) {
            return new MatchmakingState(status,
                    MatchType.valueOf(valueOf(values, "matchType")),
                    Integer.valueOf(valueOf(values, "rating")),
                    Instant.ofEpochMilli(Long.parseLong(valueOf(values, "joinedAtEpochMillis"))),
                    null);
        }
        throw new IllegalStateException("Redis must not persist IDLE matchmaking state");
    }

    private String stateKey(long userId) {
        return keyPrefix + ":state:" + userId;
    }

    private String ratedQueueKey() {
        return keyPrefix + ":queue:rated";
    }

    private String unratedQueueKey() {
        return keyPrefix + ":queue:unrated";
    }

    private String reservationKey(long userId) {
        return keyPrefix + ":reservation:" + userId;
    }

    private Optional<MatchmakingState> waitingState(long userId, MatchType matchType) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(stateKey(userId));
        if (values.isEmpty()) {
            return Optional.empty();
        }
        try {
            MatchmakingStatus status = MatchmakingStatus.valueOf(valueOf(values, "status"));
            if (status != MatchmakingStatus.WAITING
                    || MatchType.valueOf(valueOf(values, "matchType")) != matchType) {
                return Optional.empty();
            }
            return Optional.of(new MatchmakingState(status, matchType,
                    Integer.valueOf(valueOf(values, "rating")),
                    Instant.ofEpochMilli(Long.parseLong(valueOf(values, "joinedAtEpochMillis"))), null));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return Optional.empty();
        }
    }

    private boolean isInQueue(long userId, MatchType matchType) {
        String serializedUserId = Long.toString(userId);
        if (matchType == MatchType.RATED) {
            return redisTemplate.opsForZSet().score(ratedQueueKey(), serializedUserId) != null;
        }
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(unratedQueueKey(), serializedUserId));
    }

    private boolean isReserved(long userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(reservationKey(userId)));
    }

    private Long parsePositiveUserId(String value) {
        try {
            long userId = Long.parseLong(value);
            return userId > 0 ? userId : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void validateWaitingEntry(long userId, MatchType matchType, Instant joinedAt) {
        validateUserId(userId);
        if (matchType == null || joinedAt == null) {
            throw new IllegalArgumentException("Match type and joined time are required");
        }
    }

    private void validateUserId(long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("User ID must be positive");
        }
    }

    private void validatePair(long firstUserId, long secondUserId, MatchType matchType) {
        validateUserId(firstUserId);
        validateUserId(secondUserId);
        if (firstUserId == secondUserId || matchType == null) {
            throw new IllegalArgumentException("Two distinct users and match type are required");
        }
    }

    private void validateReservation(ReservedMatchPair reservation) {
        if (reservation == null || reservation.reservationToken() == null || reservation.reservationToken().isBlank()) {
            throw new IllegalArgumentException("Reservation is required");
        }
        validatePair(reservation.firstUserId(), reservation.secondUserId(), reservation.matchType());
    }

    private void validateActiveMatch(Long matchId, MatchType matchType) {
        if ((matchId == null) != (matchType == null) || (matchId != null && matchId <= 0)) {
            throw new IllegalArgumentException("Active match ID and match type must be provided together");
        }
    }

    private String nullableMatchId(Long matchId) {
        return matchId == null ? "" : Long.toString(matchId);
    }

    private String nullableMatchType(MatchType matchType) {
        return matchType == null ? "" : matchType.name();
    }

    private int requireResultCode(Long result) {
        if (result == null) {
            throw new IllegalStateException("Redis script did not return a result");
        }
        return Math.toIntExact(result);
    }

    private String valueOf(Map<Object, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) {
            throw new IllegalStateException("Missing Redis matchmaking state field: " + field);
        }
        return value.toString();
    }
}
