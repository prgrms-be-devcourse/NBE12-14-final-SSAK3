package com.react.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.ai.audience.gemini")
public record GeminiAudienceAnalysisProperties(
        String apiKey,
        String model,
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        int maxMessages,
        int maxInputCharacters,
        int maxOutputTokens,
        int maxResponseCharacters
) {
    public GeminiAudienceAnalysisProperties {
        apiKey = apiKey == null ? "" : apiKey;
        model = model == null ? "" : model;
        baseUrl = baseUrl == null ? "https://generativelanguage.googleapis.com/v1beta" : trimTrailingSlash(baseUrl);
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(15) : readTimeout;
        maxMessages = maxMessages == 0 ? 100 : maxMessages;
        maxInputCharacters = maxInputCharacters == 0 ? 32_000 : maxInputCharacters;
        maxOutputTokens = maxOutputTokens == 0 ? 2_048 : maxOutputTokens;
        maxResponseCharacters = maxResponseCharacters == 0 ? 128_000 : maxResponseCharacters;
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
