package com.react.ai.service;

import com.react.ai.dto.AudienceMessage;
import com.react.ai.dto.AudienceAnalysisLlmResponse;

import java.util.List;

/**
 * 청중 의견 분석 전용 LLM 호출 경계다. 제공자와 모델 선택은 후속 PR에서 구현한다.
 */
@FunctionalInterface
public interface AudienceAnalysisLlmClient {
    AudienceAnalysisLlmResponse analyze(List<AudienceMessage> messages);
}
