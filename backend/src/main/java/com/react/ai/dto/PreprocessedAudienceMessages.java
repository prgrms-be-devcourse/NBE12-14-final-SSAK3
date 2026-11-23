package com.react.ai.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 분석 입력의 대표 메시지와 대표 ID가 가리키는 실제 원문 ID를 함께 보존한다.
 * LLM은 대표 ID만 반환하고, 저장 전에 모든 실제 원문 ID로 확장한다.
 */
public record PreprocessedAudienceMessages(
        List<AudienceAnalysisInputMessage> inputMessages,
        Map<Long, List<Long>> originalMessageIdsByRepresentative
) {
    public PreprocessedAudienceMessages {
        inputMessages = List.copyOf(inputMessages);
        Map<Long, List<Long>> copied = new LinkedHashMap<>();
        originalMessageIdsByRepresentative.forEach((representativeId, originalIds) ->
                copied.put(representativeId, List.copyOf(originalIds)));
        originalMessageIdsByRepresentative = Map.copyOf(copied);
    }

    public List<Long> originalMessageIds(long representativeMessageId) {
        List<Long> originalIds = originalMessageIdsByRepresentative.get(representativeMessageId);
        if (originalIds == null) {
            throw new IllegalArgumentException("분석 입력에 없는 대표 메시지 ID입니다.");
        }
        return originalIds;
    }

    public AiAnalysisResult expandSourceMessageIds(AiAnalysisResult result) {
        return new AiAnalysisResult(result.topics().stream()
                .map(topic -> new AiTopicResult(
                        topic.topic(),
                        topic.summary(),
                        topic.category(),
                        topic.sourceMessageIds().stream()
                                .flatMap(representativeId -> originalMessageIds(representativeId).stream())
                                .toList()
                ))
                .toList());
    }
}
