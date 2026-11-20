package com.react.ai.exception;

public enum GeminiAudienceAnalysisErrorCode {
    RATE_LIMIT,
    HTTP_5XX,
    TIMEOUT,
    NETWORK,
    AUTH,
    HTTP_4XX,
    INVALID_RESPONSE,
    INVALID_CONFIGURATION,
    INPUT_LIMIT_EXCEEDED,
    INTERRUPTED
}
