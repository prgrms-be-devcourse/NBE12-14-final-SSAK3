package com.react.ai.service;

public class InvalidAiAnalysisResultException extends RuntimeException {
    public InvalidAiAnalysisResultException(String message) {
        super(message);
    }

    public InvalidAiAnalysisResultException(String message, Throwable cause) {
        super(message, cause);
    }
}
