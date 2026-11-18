package com.react.ai.exception;

public class InvalidAiAnalysisResultException extends RuntimeException {
    public InvalidAiAnalysisResultException(String message) {
        super(message);
    }

    public InvalidAiAnalysisResultException(String message, Throwable cause) {
        super(message, cause);
    }
}
