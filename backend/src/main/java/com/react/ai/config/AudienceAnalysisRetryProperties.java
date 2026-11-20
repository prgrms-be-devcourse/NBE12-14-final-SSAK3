package com.react.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.ai.audience.retry")
public record AudienceAnalysisRetryProperties(int maxRetries, Duration retryDelay, Duration maxRetryAfter) {
    public AudienceAnalysisRetryProperties {
        if (maxRetries < 0 || maxRetries > 1) {
            throw new IllegalArgumentException("AI 분석 Gemini 재시도 횟수는 0 또는 1이어야 합니다.");
        }
        if (retryDelay == null || retryDelay.isNegative()) {
            throw new IllegalArgumentException("AI 분석 Gemini 재시도 대기 시간은 0 이상이어야 합니다.");
        }
        if (maxRetryAfter == null || maxRetryAfter.isZero() || maxRetryAfter.isNegative()) {
            throw new IllegalArgumentException("AI 분석 Gemini Retry-After 상한은 양수여야 합니다.");
        }
    }
}
