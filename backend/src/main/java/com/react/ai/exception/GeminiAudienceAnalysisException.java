package com.react.ai.exception;

import java.time.Duration;

/** 외부 Gemini 호출 설정·전송·응답 처리 실패를 나타낸다. 민감한 요청 내용은 포함하지 않는다. */
public class GeminiAudienceAnalysisException extends RuntimeException {
    private final GeminiAudienceAnalysisErrorCode errorCode;
    private final boolean retryable;
    private final Duration retryAfter;

    public GeminiAudienceAnalysisException(String message) {
        this(GeminiAudienceAnalysisErrorCode.INVALID_RESPONSE, false, null, message);
    }

    public GeminiAudienceAnalysisException(GeminiAudienceAnalysisErrorCode errorCode, boolean retryable,
                                            Duration retryAfter, String message) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.retryAfter = retryAfter;
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

}
