package com.react.ai.dto;

/** LLM 응답 본문과 제공자가 보고한 실제 토큰 사용량이다. */
public record AudienceAnalysisLlmResponse(String content, String modelName, Usage usage) {
    public AudienceAnalysisLlmResponse(String content, String modelName) {
        this(content, modelName, null);
    }

    public record Usage(Long promptTokenCount, Long outputTokenCount, Long totalTokenCount) {
    }
}
