package com.react.chat.dto;

import com.react.global.exception.ErrorCode;

/** 채팅 전송이 거절되었을 때 보낸 사람에게만 가는 오류. 명세의 /user/queue/errors 형식이다. */
public record ChatErrorEvent(String type, String code, String message) {
    public static final String TYPE = "ERROR";

    public static ChatErrorEvent of(ErrorCode errorCode) {
        return new ChatErrorEvent(TYPE, errorCode.name(), errorCode.getMessage());
    }
}
