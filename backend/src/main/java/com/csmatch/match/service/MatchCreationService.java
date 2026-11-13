package com.csmatch.match.service;

import com.csmatch.global.exception.BusinessException;
import com.csmatch.global.exception.ErrorCode;
import com.csmatch.match.dto.ActiveMatch;
import com.csmatch.match.entity.Match;
import com.csmatch.match.entity.MatchPlayer;
import com.csmatch.match.entity.MatchStatus;
import com.csmatch.match.repository.MatchPlayerRepository;
import com.csmatch.match.repository.MatchRepository;
import com.csmatch.matchmaking.entity.MatchType;
import com.csmatch.user.entity.User;
import com.csmatch.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class MatchCreationService {
    private static final List<MatchStatus> ACTIVE_STATUSES = List.of(MatchStatus.PREPARING, MatchStatus.PLAYING);

    private final UserRepository users;
    private final MatchRepository matches;
    private final MatchPlayerRepository matchPlayers;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Match createPreparingMatch(MatchType matchType, Long firstUserId, Long secondUserId) {
        validatePlayers(matchType, firstUserId, secondUserId);

        Long lowerUserId = Math.min(firstUserId, secondUserId);
        Long higherUserId = Math.max(firstUserId, secondUserId);
        User lowerUser = lockUser(lowerUserId);
        User higherUser = lockUser(higherUserId);
        User first = firstUserId.equals(lowerUserId) ? lowerUser : higherUser;
        User second = secondUserId.equals(lowerUserId) ? lowerUser : higherUser;
        if (hasActiveMatch(firstUserId) || hasActiveMatch(secondUserId)) {
            throw new BusinessException(ErrorCode.MATCH_IN_PROGRESS);
        }

        Match match = matches.save(Match.preparing(matchType));
        matchPlayers.saveAll(List.of(
                MatchPlayer.preparing(match, first.getUserId(), first.getRating()),
                MatchPlayer.preparing(match, second.getUserId(), second.getRating())
        ));
        return match;
    }

    @Transactional(readOnly = true)
    public boolean hasActiveMatch(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("User ID must be positive");
        }
        return matchPlayers.existsByUserIdAndMatchStatusIn(userId, ACTIVE_STATUSES);
    }

    @Transactional(readOnly = true)
    public Optional<ActiveMatch> findActiveMatch(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("User ID must be positive");
        }
        return matchPlayers.findActiveMatchByUserIdAndMatchStatusIn(userId, ACTIVE_STATUSES);
    }

    private User lockUser(Long userId) {
        return users.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private void validatePlayers(MatchType matchType, Long firstUserId, Long secondUserId) {
        if (matchType == null || firstUserId == null || secondUserId == null
                || firstUserId <= 0 || secondUserId <= 0 || firstUserId.equals(secondUserId)) {
            throw new IllegalArgumentException("Match type and two distinct positive user IDs are required");
        }
    }
}
