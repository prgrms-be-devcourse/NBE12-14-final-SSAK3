package com.react.ai.dto;

import com.react.ai.domain.AiAnalysisStatus;

public record AiAnalysisProcessResult(long analysisId, AiAnalysisStatus status, boolean created, int topicGroupCount) {
}
