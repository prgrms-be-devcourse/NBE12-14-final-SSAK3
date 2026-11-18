package com.react.ai.service;

import com.react.ai.dto.AiAnalysisListView;
import com.react.ai.dto.AiAnalysisView;
import com.react.ai.dto.AiSourceMessageView;
import com.react.ai.dto.AiTopicGroupMessagesView;
import com.react.ai.dto.AiTopicGroupView;
import com.react.ai.repository.AiAnalysisQueryRepository;
import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AiAnalysisQueryService {
    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 50;
    private static final ZoneOffset STORAGE_OFFSET = ZoneOffset.ofHours(9);

    private final AiAnalysisQueryRepository repository;

    public AiAnalysisListView getAnalyses(long roomId, String hostEmail, int limit) {
        validateLimit(limit);
        requireOwnedRoom(roomId, hostEmail);

        var analyses = repository.findRecentAnalyses(roomId, limit);
        var topicGroupsByAnalysis = topicGroupsByAnalysis(analyses);
        List<AiAnalysisView> views = analyses.stream()
                .map(analysis -> new AiAnalysisView(
                        analysis.analysisId(),
                        toInstant(analysis.windowStartAt()),
                        toInstant(analysis.windowEndAt()),
                        analysis.status(),
                        toInstant(analysis.completedAt()),
                        topicGroupsByAnalysis.getOrDefault(analysis.analysisId(), List.of())
                ))
                .toList();

        return new AiAnalysisListView(roomId, views,
                repository.findLastCompletedAt(roomId).map(this::toInstant).orElse(null));
    }

    public AiTopicGroupMessagesView getTopicGroupMessages(long roomId, long topicGroupId, String hostEmail) {
        requireOwnedRoom(roomId, hostEmail);
        var group = repository.findTopicGroupInRoom(roomId, topicGroupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        List<AiSourceMessageView> messages = repository.findTopicGroupMessages(roomId, topicGroupId).stream()
                .map(message -> new AiSourceMessageView(
                        message.messageId(),
                        message.participantId(),
                        message.nickname(),
                        message.content(),
                        toInstant(message.receivedAt())
                ))
                .toList();
        return new AiTopicGroupMessagesView(group.topicGroupId(), group.topic(), messages);
    }

    private Map<Long, List<AiTopicGroupView>> topicGroupsByAnalysis(
            List<AiAnalysisQueryRepository.AnalysisRow> analyses) {
        var topicRows = repository.findTopicGroups(analyses.stream()
                .map(AiAnalysisQueryRepository.AnalysisRow::analysisId).toList());
        Map<Long, List<Long>> sourceIdsByTopicGroup = new HashMap<>();
        for (var source : repository.findTopicGroupMessageIds(topicRows.stream()
                .map(AiAnalysisQueryRepository.TopicGroupRow::topicGroupId).toList())) {
            sourceIdsByTopicGroup.computeIfAbsent(source.topicGroupId(), ignored -> new ArrayList<>())
                    .add(source.chatMessageId());
        }

        return topicRows.stream().collect(Collectors.groupingBy(
                AiAnalysisQueryRepository.TopicGroupRow::analysisId,
                java.util.LinkedHashMap::new,
                Collectors.mapping(topic -> new AiTopicGroupView(
                                topic.topicGroupId(), topic.topic(), topic.category(), topic.summary(),
                                topic.messageCount(),
                                sourceIdsByTopicGroup.getOrDefault(topic.topicGroupId(), List.of())
                        ), Collectors.toList())
        ));
    }

    private void requireOwnedRoom(long roomId, String hostEmail) {
        var room = repository.findRoomOwner(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!room.email().equalsIgnoreCase(hostEmail)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private void validateLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(STORAGE_OFFSET);
    }
}
