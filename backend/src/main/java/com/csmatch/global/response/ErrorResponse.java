package com.csmatch.global.response;

import com.csmatch.global.exception.ErrorCode;

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
