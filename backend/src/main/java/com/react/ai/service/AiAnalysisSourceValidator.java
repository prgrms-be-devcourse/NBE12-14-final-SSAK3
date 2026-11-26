package com.react.ai.service;

import com.react.ai.dto.AiAnalysisResult;
import com.react.ai.exception.InvalidAiAnalysisResultException;

import java.util.HashSet;
import java.util.Set;

/** LLM이 반환한 ID의 출처와 그룹 간 중복을 검증한다. */
public class AiAnalysisSourceValidator {
    public void validate(Set<Long> allowedIds, AiAnalysisResult result) {
        Set<Long> assignedIds = new HashSet<>();
        result.topics().forEach(topic -> topic.sourceMessageIds().forEach(id -> {
            if (!allowedIds.contains(id)) {
                throw new InvalidAiAnalysisResultException("분석 대상이 아닌 메시지를 참조했습니다.");
            }
            if (!assignedIds.add(id)) {
                throw new InvalidAiAnalysisResultException("하나의 메시지는 하나의 주제 그룹에만 연결할 수 있습니다.");
            }
        }));
    }
}
