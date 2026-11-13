package com.csmatch.matchmaking.service;

import com.csmatch.global.exception.BusinessException;
import com.csmatch.matchmaking.repository.MatchmakingRedisRepository;
import java.time.Instant;
import java.util.LinkedHashSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@Slf4j
@RequiredArgsConstructor
public class MatchmakingSearchScheduler {
    private final MatchmakingRedisRepository matchmakingRedisRepository;
    private final MatchmakingService matchmakingService;

    @Scheduled(fixedDelay = 10_000)
    public void rematchRatedWaiters() {
        rematchRatedWaiters(Instant.now());
    }

    public void rematchRatedWaiters(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("Current time is required");
        }

        for (Long userId : new LinkedHashSet<>(matchmakingRedisRepository.findRatedWaitingUserIds())) {
            try {
                matchmakingService.tryCreateAndConfirmMatch(userId, now);
            } catch (BusinessException exception) {
                log.warn("Rated matchmaking search skipped user after business conflict. userId={}, errorCode={}",
                        userId, exception.getErrorCode());
            } catch (RuntimeException exception) {
                log.error("Rated matchmaking search failed for user. userId={}", userId, exception);
            }
        }
    }
}
