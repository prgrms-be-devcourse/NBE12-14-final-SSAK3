package com.react.report.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.react.report.service.ReportGenerationException.ProviderFailure;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.EOFException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Map;

public final class OpenAiReportOverviewClient implements ReportOverviewClient {
    private final RestClient http;
    private final ObjectReader json;
    private final String apiKey;
    private final String model;
    private final String instructions;
    private final JsonNode schema;

    public OpenAiReportOverviewClient(RestClient http, ObjectMapper mapper, String apiKey, String model,
                                      String instructions, JsonNode schema) {
        this.http = http;
        this.json = mapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
        this.apiKey = apiKey;
        this.model = model;
        this.instructions = instructions;
        this.schema = schema;
    }

    @Override
    public JsonNode generate(JsonNode input) {
        if (apiKey.isBlank()) throw ReportGenerationException.providerFailure(ProviderFailure.OPENAI_API_KEY_MISSING);
        if (model.isBlank()) throw ReportGenerationException.providerFailure(ProviderFailure.OPENAI_MODEL_MISSING);
        var format = Map.of("type", "json_schema", "name", "lecture_report", "strict", true, "schema", schema);
        var body = Map.of("model", model, "store", false, "reasoning", Map.of("effort", "low"),
                "service_tier", "default", "instructions", instructions, "input", input.toString(),
                "text", Map.of("format", format));
        String raw;
        try {
            raw = http.post().uri("https://api.openai.com/v1/responses")
                    .headers(headers -> headers.setBearerAuth(apiKey))
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 429) throw ReportGenerationException.providerFailure(classifyRateLimit(ex));
            throw ReportGenerationException.httpFailure(ex.getStatusCode().value());
        } catch (RestClientException ex) {
            // 공급자 응답 본문과 키를 저장하거나 로그로 전달하지 않는다.
            throw ReportGenerationException.providerFailure(classifyTransport(ex));
        }
        JsonNode response = parseObject(raw);
        if (!"completed".equals(response.path("status").textValue())) {
            throw ReportGenerationException.providerFailure(ProviderFailure.OPENAI_INCOMPLETE_RESPONSE);
        }
        var text = new StringBuilder();
        for (var item : response.path("output")) {
            if (!"message".equals(item.path("type").textValue())) continue;
            for (var part : item.path("content")) {
                if ("refusal".equals(part.path("type").textValue())) {
                    throw ReportGenerationException.providerFailure(ProviderFailure.OPENAI_REFUSAL);
                }
                if ("output_text".equals(part.path("type").textValue()) && part.path("text").isTextual()) {
                    text.append(part.get("text").textValue());
                }
            }
        }
        return parseObject(text.toString());
    }

    private ProviderFailure classifyRateLimit(RestClientResponseException exception) {
        String body = exception.getResponseBodyAsString();
        try {
            if (!body.isBlank()) {
                JsonNode response = json.readValue(body);
                if (response == null || !response.path("error").isObject()) return ProviderFailure.OPENAI_HTTP_429_UNCLASSIFIED;
                JsonNode error = response.get("error");
                String code = error.path("code").asText("");
                String type = error.path("type").asText("");
                if (isQuotaError(code) || isQuotaError(type)) return ProviderFailure.OPENAI_HTTP_429_QUOTA;
                if (code.equals("rate_limit_exceeded") || code.equals("slow_down")
                        || type.equals("rate_limit_error") || type.equals("tokens") || type.equals("requests")) {
                    return ProviderFailure.OPENAI_HTTP_429_RATE_LIMIT;
                }
            }
            return hasRetryAfter(exception.getResponseHeaders())
                    ? ProviderFailure.OPENAI_HTTP_429_RATE_LIMIT : ProviderFailure.OPENAI_HTTP_429_UNCLASSIFIED;
        } catch (JsonProcessingException ignored) {
            return ProviderFailure.OPENAI_HTTP_429_UNCLASSIFIED;
        }
    }

    private boolean isQuotaError(String value) {
        return switch (value) {
            case "insufficient_quota", "credit_balance_exhausted", "organization_spend_limit_exceeded",
                    "project_spend_limit_exceeded", "organization_usage_limit_exceeded" -> true;
            default -> false;
        };
    }

    private boolean hasRetryAfter(HttpHeaders headers) {
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) return false;
        if (value.matches("[0-9]+")) return true;
        try {
            return headers.getFirstDate(HttpHeaders.RETRY_AFTER) >= 0;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private ProviderFailure classifyTransport(RestClientException exception) {
        if (Thread.currentThread().isInterrupted()) return ProviderFailure.OPENAI_TRANSPORT;
        for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) return ProviderFailure.OPENAI_TIMEOUT;
            if (cause instanceof SocketException || cause instanceof UnknownHostException || cause instanceof EOFException) {
                return ProviderFailure.OPENAI_NETWORK;
            }
        }
        return ProviderFailure.OPENAI_TRANSPORT;
    }

    private JsonNode parseObject(String value) {
        if (value == null || value.isBlank()) throw ReportGenerationException.invalidFormat();
        try {
            JsonNode result = json.readValue(value);
            if (!result.isObject()) throw ReportGenerationException.invalidFormat();
            return result;
        } catch (JsonProcessingException ex) {
            throw ReportGenerationException.invalidFormat();
        }
    }
}
