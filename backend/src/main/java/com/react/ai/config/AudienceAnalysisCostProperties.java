package com.react.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Optional;

/** 현재 설정된 Gemini 모델의 토큰 단가다. 단가 미설정 시 추정 비용은 기록하지 않는다. */
@ConfigurationProperties("app.ai.audience.gemini.pricing")
public record AudienceAnalysisCostProperties(
        String inputUsdPerMillionTokens,
        String outputUsdPerMillionTokens
) {
    public AudienceAnalysisCostProperties {
        inputUsdPerMillionTokens = normalize(inputUsdPerMillionTokens);
        outputUsdPerMillionTokens = normalize(outputUsdPerMillionTokens);
        if ((inputUsdPerMillionTokens == null) != (outputUsdPerMillionTokens == null)) {
            throw new IllegalArgumentException("Gemini 입력·출력 토큰 단가는 함께 설정해야 합니다.");
        }
        validate(inputUsdPerMillionTokens);
        validate(outputUsdPerMillionTokens);
    }

    public Optional<ModelPricing> pricing() {
        if (inputUsdPerMillionTokens == null) {
            return Optional.empty();
        }
        return Optional.of(new ModelPricing(new BigDecimal(inputUsdPerMillionTokens),
                new BigDecimal(outputUsdPerMillionTokens)));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void validate(String value) {
        if (value == null) {
            return;
        }
        try {
            if (new BigDecimal(value).signum() < 0) {
                throw new IllegalArgumentException("Gemini 토큰 단가는 0 이상이어야 합니다.");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Gemini 토큰 단가는 숫자여야 합니다.", e);
        }
    }

    public record ModelPricing(BigDecimal inputUsdPerMillionTokens, BigDecimal outputUsdPerMillionTokens) {
    }
}
