package com.react.ai.service;

import com.react.ai.dto.AiQuestionGroupListView;
import com.react.ai.dto.AiQuestionGroupView;
import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.repository.AiQuestionGroupRepository;
import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class AiQuestionGroupService {
    private static final ZoneOffset STORAGE_OFFSET = ZoneOffset.ofHours(9);
    private final AiQuestionGroupRepository repository;

    @Transactional(readOnly = true)
    public AiQuestionGroupListView list(long roomId, String hostEmail, String answerStatus, int limit, Long cursor) {
        requireOwner(roomId, hostEmail);
        if (!"UNANSWERED".equals(answerStatus) && !"ANSWERED".equals(answerStatus))
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        if (limit < 1 || limit > 50 || (cursor != null && cursor < 1))
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        if (cursor != null) {
            var cursorGroup = repository.findGroup(roomId, cursor)
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT));
            if (!"QUESTION".equals(cursorGroup.category()) || cursorGroup.analysisStatus() != AiAnalysisStatus.COMPLETED) {
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
        }
        var rows = repository.findQuestions(roomId, "ANSWERED".equals(answerStatus), cursor, limit + 1);
        boolean hasNext = rows.size() > limit;
        var page = rows.stream().limit(limit).toList();
        return new AiQuestionGroupListView(roomId, page.stream().map(this::view).toList(),
                hasNext ? page.getLast().topicGroupId() : null);
    }

    @Transactional
    public AiQuestionGroupView change(long roomId, long topicGroupId, String hostEmail, boolean answered) {
        long hostId = requireOwner(roomId, hostEmail);
        var before = repository.findGroup(roomId, topicGroupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!"QUESTION".equals(before.category()) || before.analysisStatus() != AiAnalysisStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        repository.changeAnswerStatus(roomId, topicGroupId, hostId, answered);
        // 조건부 UPDATE가 경합에서 0행이면 일반 SELECT의 과거 스냅샷 대신 현재 읽기로 멱등 응답을 만든다.
        var current = repository.findAnswerStateForUpdate(roomId, topicGroupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return view(before, current.answeredAt());
    }

    private long requireOwner(long roomId, String hostEmail) {
        var owner = repository.findOwner(roomId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!owner.email().equalsIgnoreCase(hostEmail)) throw new BusinessException(ErrorCode.FORBIDDEN);
        return owner.id();
    }

    private AiQuestionGroupView view(AiQuestionGroupRepository.QuestionGroup group) {
        return view(group, group.answeredAt());
    }

    private AiQuestionGroupView view(AiQuestionGroupRepository.QuestionGroup group, LocalDateTime answeredAt) {
        return new AiQuestionGroupView(group.topicGroupId(), group.analysisId(), group.topic(), group.summary(),
                group.messageCount(), instant(group.windowEndAt()),
                answeredAt == null ? "UNANSWERED" : "ANSWERED", instant(answeredAt));
    }

    private Instant instant(LocalDateTime time) {
        return time == null ? null : time.toInstant(STORAGE_OFFSET);
    }
}
