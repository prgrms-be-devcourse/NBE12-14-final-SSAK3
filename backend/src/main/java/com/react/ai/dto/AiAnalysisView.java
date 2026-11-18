package com.react.ai.dto;

import com.react.ai.domain.AiAnalysisStatus;

import java.time.Instant;
import java.util.List;

public record AiAnalysisView(long analysisId, Instant windowStartAt, Instant windowEndAt,
                             AiAnalysisStatus status, Instant completedAt, List<AiTopicGroupView> topicGroups) {
}
