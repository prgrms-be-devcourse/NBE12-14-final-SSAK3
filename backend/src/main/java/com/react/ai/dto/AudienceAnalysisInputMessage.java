package com.react.ai.dto;

/** Gemini에 전달하는 대표 청중 메시지다. occurrenceCount는 동의자 수가 아닌 실제 발생 횟수다. */
public record AudienceAnalysisInputMessage(long messageId, String content, int occurrenceCount) {
}
