package com.react.ai.dto;

import java.time.Instant;

public record AiQuestionGroupView(long topicGroupId, long analysisId, String topic, String summary,
                                  long messageCount, Instant windowEndAt, String answerStatus, Instant answeredAt) {
}
