package com.react.ai.exception;

import com.react.ai.dto.AudienceAnalysisLlmResponse;

import java.time.Duration;

/** 외부 Gemini 호출 설정·전송·응답 처리 실패를 나타낸다. 민감한 요청 내용은 포함하지 않는다. */
public class GeminiAudienceAnalysisException extends RuntimeException {
    private final GeminiAudienceAnalysisErrorCode errorCode;
    private final boolean retryable;
    private final Duration retryAfter;
    private final AudienceAnalysisLlmResponse.Usage usage;

    public GeminiAudienceAnalysisException(String message) {
        this(GeminiAudienceAnalysisErrorCode.INVALID_RESPONSE, false, null, null, message);
    }

    public GeminiAudienceAnalysisException(GeminiAudienceAnalysisErrorCode errorCode, boolean retryable,
                                            Duration retryAfter, String message) {
        this(errorCode, retryable, retryAfter, null, message);
    }

    public GeminiAudienceAnalysisException(GeminiAudienceAnalysisErrorCode errorCode, boolean retryable,
                                            Duration retryAfter, AudienceAnalysisLlmResponse.Usage usage,
                                            String message) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.retryAfter = retryAfter;
        this.usage = usage;
    }

    public GeminiAudienceAnalysisErrorCode errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    /** HTTP 200 Gemini 응답에서 검증 전에 추출한 사용량이다. 전송 오류 등에서는 null이다. */
    public AudienceAnalysisLlmResponse.Usage usage() {
        return usage;
    }

}
