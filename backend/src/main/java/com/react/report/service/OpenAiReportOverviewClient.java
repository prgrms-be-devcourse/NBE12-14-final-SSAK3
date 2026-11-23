package com.react.report.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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
        if (apiKey.isBlank() || model.isBlank()) {
            throw ReportGenerationException.requestFailed();
        }
        var format = Map.of("type", "json_schema", "name", "lecture_report", "strict", true, "schema", schema);
        var body = Map.of("model", model, "store", false, "reasoning", Map.of("effort", "low"),
                "service_tier", "default", "instructions", instructions, "input", input.toString(),
                "text", Map.of("format", format));
        String raw;
        try {
            raw = http.post().uri("https://api.openai.com/v1/responses")
                    .headers(headers -> headers.setBearerAuth(apiKey))
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
        } catch (RestClientException ex) {
            // 공급자 응답 본문과 키를 저장하거나 로그로 전달하지 않는다.
            throw ReportGenerationException.requestFailed();
        }
        JsonNode response = parseObject(raw);
        if (!"completed".equals(response.path("status").textValue())) {
            throw ReportGenerationException.requestFailed();
        }
        var text = new StringBuilder();
        for (var item : response.path("output")) {
            if (!"message".equals(item.path("type").textValue())) continue;
            for (var part : item.path("content")) {
                if ("refusal".equals(part.path("type").textValue())) throw ReportGenerationException.requestFailed();
                if ("output_text".equals(part.path("type").textValue()) && part.path("text").isTextual()) {
                    text.append(part.get("text").textValue());
                }
            }
        }
        return parseObject(text.toString());
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
