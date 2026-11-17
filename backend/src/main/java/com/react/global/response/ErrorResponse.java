package com.react.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.react.global.exception.ErrorCode;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ErrorResponse(
        String code,
        String message,
        Object details,
        String timestamp
) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return of(errorCode, null);
    }

    public static ErrorResponse of(
            ErrorCode errorCode,
            Object details
    ) {
        return new ErrorResponse(
                errorCode.name(),
                errorCode.getMessage(),
                details,
                Instant.now().toString()
        );
    }
}
