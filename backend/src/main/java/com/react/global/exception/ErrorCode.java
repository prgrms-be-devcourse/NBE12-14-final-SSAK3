package com.react.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    // 공통 요청 오류: 400
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    INVALID_NICKNAME(HttpStatus.BAD_REQUEST, "닉네임이 올바르지 않습니다."),
    INVALID_POLL_OPTION(HttpStatus.BAD_REQUEST, "유효하지 않은 투표 선택지입니다."),

    // 인증 오류: 401
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "인증이 필요하거나 인증이 만료되었습니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),

    // 권한 오류: 403
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),

    // 리소스 오류: 404
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 대상을 찾을 수 없습니다."),
    ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "강연방을 찾을 수 없습니다."),
    INVALID_JOIN_CODE(HttpStatus.NOT_FOUND, "유효하지 않은 강연 참여 코드입니다."),
    POLL_NOT_FOUND(HttpStatus.NOT_FOUND, "투표를 찾을 수 없습니다."),

    // 중복 및 상태 충돌: 409
    CONFLICT(HttpStatus.CONFLICT, "현재 상태와 충돌하는 요청입니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    INVALID_ROOM_STATUS(HttpStatus.CONFLICT, "현재 강연 상태에서는 요청을 처리할 수 없습니다."),
    ROOM_NOT_LIVE(HttpStatus.CONFLICT, "진행 중인 강연이 아닙니다."),
    ROOM_ENDED(HttpStatus.CONFLICT, "이미 종료된 강연입니다."),
    POLL_ALREADY_CLOSED(HttpStatus.CONFLICT, "이미 종료된 투표입니다."),
    POLL_CLOSED(HttpStatus.CONFLICT, "종료된 투표에는 응답할 수 없습니다."),
    ALREADY_VOTED(HttpStatus.CONFLICT, "이미 응답한 투표입니다."),
    ROOM_NOT_ENDED(HttpStatus.CONFLICT, "강연이 종료된 후 보고서를 생성할 수 있습니다."),
    REPORT_INPUT_NOT_READY(HttpStatus.CONFLICT, "회고 보고서 생성에 필요한 데이터가 준비되지 않았습니다."),

    // 요청 제한: 429
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청 횟수가 초과되었습니다."),

    // 서버 및 외부 서비스 오류
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
    LLM_REQUEST_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "AI 서비스 요청에 실패했습니다."),

    // HTTP 프로토콜 오류
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type입니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
