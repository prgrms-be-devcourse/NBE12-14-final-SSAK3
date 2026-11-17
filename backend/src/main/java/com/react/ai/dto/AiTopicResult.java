package com.react.ai.dto;

import com.react.ai.domain.TopicCategory;

import java.util.List;

public record AiTopicResult(String topic, String summary, TopicCategory category, List<Long> sourceMessageIds) {
}
