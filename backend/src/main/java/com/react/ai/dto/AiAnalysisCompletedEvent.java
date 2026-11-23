package com.react.ai.dto;

import com.react.ai.domain.TopicCategory;
import com.react.ai.repository.AiAnalysisQueryRepository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** DASH-04 AI 분석 완료 STOMP 이벤트. 원문·Gemini 원본 응답은 포함하지 않는다. */
public record AiAnalysisCompletedEvent(String type, Data data) {
    public static final String TYPE = "AI_ANALYSIS_COMPLETED";
    private static final ZoneOffset STORAGE_OFFSET = ZoneOffset.ofHours(9);

    public record Data(long analysisId, long roomId, Instant completedAt, List<TopicGroup> topicGroups) {
    }

    public record TopicGroup(long topicGroupId, String topic, TopicCategory category,
                             String summary, long messageCount) {
    }

    public static AiAnalysisCompletedEvent of(long roomId, AiAnalysisQueryRepository.AnalysisRow analysis,
                                               List<AiAnalysisQueryRepository.TopicGroupRow> groups) {
        return new AiAnalysisCompletedEvent(TYPE, new Data(analysis.analysisId(), roomId,
                toInstant(analysis.completedAt()), groups.stream()
                .map(group -> new TopicGroup(group.topicGroupId(), group.topic(), group.category(),
                        group.summary(), group.messageCount()))
                .toList()));
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(STORAGE_OFFSET);
    }
}
