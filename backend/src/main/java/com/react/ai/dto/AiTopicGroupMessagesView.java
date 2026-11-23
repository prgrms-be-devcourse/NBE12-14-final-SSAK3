package com.react.ai.dto;

import java.util.List;

public record AiTopicGroupMessagesView(long topicGroupId, String topic, List<AiSourceMessageView> messages) {
}
