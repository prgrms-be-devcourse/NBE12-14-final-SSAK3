package com.react.global.response;

import com.react.global.exception.ErrorCode;

public record ErrorResponse(ErrorDetail error) {

    public record ErrorDetail(
            String code,
            String message
    ) {
    }

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(
                new ErrorDetail(
                        errorCode.name(),
                        errorCode.getMessage()
                )
        );
    }
}
