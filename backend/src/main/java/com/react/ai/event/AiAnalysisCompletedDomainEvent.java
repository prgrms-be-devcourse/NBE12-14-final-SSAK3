package com.react.ai.event;

/** 분석 결과와 근거 연결이 같은 트랜잭션으로 저장된 뒤 방송할 내부 이벤트다. */
public record AiAnalysisCompletedDomainEvent(long roomId, long analysisId) {
}
