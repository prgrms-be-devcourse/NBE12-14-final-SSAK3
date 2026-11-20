package com.react.ai.dto;

import com.react.ai.domain.AiLlmCallStatus;

import java.math.BigDecimal;

/** Gemini 한 번의 HTTP 호출에 대한 사용량 이력이다. 비용은 실제 청구액이 아닌 설정 단가 기반 추정치다. */
public record AiLlmCallUsage(
        long analysisId,
        int attemptNumber,
        String modelName,
        AiLlmCallStatus callStatus,
        String errorCode,
        Long promptTokenCount,
        Long outputTokenCount,
        Long totalTokenCount,
        long durationMs,
        BigDecimal inputUsdPerMillionTokens,
        BigDecimal outputUsdPerMillionTokens,
        Long estimatedCostMicrousd
) {
}
