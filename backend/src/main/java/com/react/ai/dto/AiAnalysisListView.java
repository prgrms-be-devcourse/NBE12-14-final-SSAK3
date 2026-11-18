package com.react.ai.dto;

import java.time.Instant;
import java.util.List;

public record AiAnalysisListView(long roomId, List<AiAnalysisView> analyses, Instant lastCompletedAt) {
}
