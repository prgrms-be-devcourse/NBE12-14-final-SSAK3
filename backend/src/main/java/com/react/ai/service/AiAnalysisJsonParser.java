package com.react.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.ai.domain.TopicCategory;
import com.react.ai.dto.AiAnalysisResult;
import com.react.ai.dto.AiTopicResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AiAnalysisJsonParser {
    private static final Set<String> ROOT_FIELDS = Set.of("topics");
    private static final Set<String> TOPIC_FIELDS = Set.of("topic", "summary", "category", "sourceMessageIds");
    private final ObjectMapper objectMapper;

    public AiAnalysisJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AiAnalysisResult parse(String rawResult) {
        try {
            JsonNode root = objectMapper.readTree(rawResult);
            requireObject(root, "최상위 결과");
            requireExactFields(root, ROOT_FIELDS, "최상위 결과");
            JsonNode topics = root.get("topics");
            if (!topics.isArray()) {
                throw invalid("topics는 배열이어야 합니다.");
            }

            List<AiTopicResult> results = new ArrayList<>();
            for (JsonNode topic : topics) {
                requireObject(topic, "주제 결과");
                requireExactFields(topic, TOPIC_FIELDS, "주제 결과");
                String topicName = requiredText(topic, "topic");
                if (topicName.codePointCount(0, topicName.length()) > 200) {
                    throw invalid("topic은 200자를 초과할 수 없습니다.");
                }
                results.add(new AiTopicResult(
                        topicName,
                        requiredText(topic, "summary"),
                        category(topic),
                        sourceMessageIds(topic)
                ));
            }
            return new AiAnalysisResult(List.copyOf(results));
        } catch (JsonProcessingException e) {
            throw new InvalidAiAnalysisResultException("LLM 응답이 올바른 JSON이 아닙니다.", e);
        }
    }

    private TopicCategory category(JsonNode topic) {
        try {
            return TopicCategory.valueOf(requiredText(topic, "category"));
        } catch (IllegalArgumentException e) {
            throw invalid("허용되지 않은 category입니다.");
        }
    }

    private List<Long> sourceMessageIds(JsonNode topic) {
        JsonNode ids = topic.get("sourceMessageIds");
        if (!ids.isArray() || ids.isEmpty()) {
            throw invalid("sourceMessageIds는 비어 있지 않은 배열이어야 합니다.");
        }
        List<Long> result = new ArrayList<>();
        Set<Long> uniqueIds = new HashSet<>();
        for (JsonNode id : ids) {
            if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0) {
                throw invalid("sourceMessageIds에는 양의 정수만 포함할 수 있습니다.");
            }
            if (!uniqueIds.add(id.longValue())) {
                throw invalid("sourceMessageIds에 중복된 메시지 ID가 있습니다.");
            }
            result.add(id.longValue());
        }
        return List.copyOf(result);
    }

    private String requiredText(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid(fieldName + "은 비어 있지 않은 문자열이어야 합니다.");
        }
        return value.textValue();
    }

    private void requireObject(JsonNode node, String fieldName) {
        if (!node.isObject()) {
            throw invalid(fieldName + "는 객체여야 합니다.");
        }
    }

    private void requireExactFields(JsonNode node, Set<String> fields, String name) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(fields)) {
            throw invalid(name + "의 필드가 명세와 일치하지 않습니다.");
        }
    }

    private InvalidAiAnalysisResultException invalid(String message) {
        return new InvalidAiAnalysisResultException(message);
    }
}
