package com.react.ai.service;

import com.react.ai.config.AudienceAnalysisCostProperties;
import com.react.ai.dto.AudienceAnalysisLlmResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/** Gemini가 반환한 실제 토큰 사용량에 설정 단가를 적용해 추정 비용만 계산한다. */
public class AiLlmCostCalculator {
    public Optional<CostEstimate> estimate(AudienceAnalysisLlmResponse.Usage usage,
                                           AudienceAnalysisCostProperties properties) {
        if (usage == null || usage.promptTokenCount() == null || usage.outputTokenCount() == null) {
            return Optional.empty();
        }
        if (usage.totalTokenCount() != null
                && usage.totalTokenCount() != usage.promptTokenCount() + usage.outputTokenCount()) {
            return Optional.empty();
        }
        return properties.pricing().map(pricing -> new CostEstimate(
                pricing.inputUsdPerMillionTokens(), pricing.outputUsdPerMillionTokens(),
                pricing.inputUsdPerMillionTokens().multiply(BigDecimal.valueOf(usage.promptTokenCount()))
                        .add(pricing.outputUsdPerMillionTokens().multiply(BigDecimal.valueOf(usage.outputTokenCount())))
                        .setScale(0, RoundingMode.HALF_UP).longValueExact()
        ));
    }

    public record CostEstimate(BigDecimal inputUsdPerMillionTokens, BigDecimal outputUsdPerMillionTokens,
                               long estimatedCostMicrousd) {
    }
}
