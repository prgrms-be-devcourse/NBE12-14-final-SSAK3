package com.react.ai.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 한 Gemini 요청에 포함할 대표 메시지와 원본 ID의 고정된 묶음이다. */
public record AudienceAnalysisBatch(int batchIndex, List<AudienceAnalysisInputMessage> inputMessages,
                                    Map<Long, List<Long>> originalMessageIdsByRepresentative) {
    public AudienceAnalysisBatch {
        inputMessages = List.copyOf(inputMessages);
        Map<Long, List<Long>> copied = new LinkedHashMap<>();
        originalMessageIdsByRepresentative.forEach((id, originals) -> copied.put(id, List.copyOf(originals)));
        originalMessageIdsByRepresentative = Map.copyOf(copied);
    }

    public int inputCharacterCount() {
        return inputMessages.stream().mapToInt(message -> message.content().length()).sum();
    }
}
