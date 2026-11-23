package com.react.ai.dto;

import com.react.ai.domain.TopicCategory;

import java.util.List;

public record AiTopicGroupView(long topicGroupId, String topic, TopicCategory category, String summary,
                               long messageCount, List<Long> sourceMessageIds) {
}
