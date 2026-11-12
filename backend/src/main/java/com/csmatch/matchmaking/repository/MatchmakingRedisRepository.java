package com.csmatch.matchmaking.repository;

import com.csmatch.matchmaking.entity.MatchType;
import com.csmatch.matchmaking.dto.MatchmakingState;
import com.csmatch.matchmaking.entity.MatchmakingStatus;
import com.csmatch.matchmaking.entity.QueueRegistrationResult;
import com.csmatch.matchmaking.entity.QueueRemovalResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
                List.of(stateKey(userId), ratedQueueKey(), unratedQueueKey()), Long.toString(userId));
        return switch (requireResultCode(result)) {
            case 0 -> QueueRemovalResult.NOT_IN_QUEUE;
            case 1 -> QueueRemovalResult.REMOVED;
            case 2 -> QueueRemovalResult.ALREADY_MATCHED;
            default -> throw new IllegalStateException("Unexpected Redis removal result");
        };
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
