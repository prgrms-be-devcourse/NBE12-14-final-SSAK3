package com.csmatch.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    // ===== 공통 오류 =====

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요하거나 인증이 만료되었습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 대상을 찾을 수 없습니다."),
    CONFLICT(HttpStatus.CONFLICT, "현재 상태와 충돌하는 요청입니다."),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청 횟수가 초과되었습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type입니다."),

    // ===== 회원 및 인증 =====

    EMAIL_DUPLICATED(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    NICKNAME_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    MATCH_IN_PROGRESS(HttpStatus.CONFLICT, "이미 진행 중인 대결이 있습니다."),

    // ===== 프로필 이미지 =====

    INVALID_IMAGE_FORMAT(HttpStatus.BAD_REQUEST, "지원하지 않는 이미지 형식입니다."),
    IMAGE_SIZE_EXCEEDED(HttpStatus.BAD_REQUEST, "허용된 이미지 크기를 초과했습니다."),
    IMAGE_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이미지 업로드에 실패했습니다."),
    PROFILE_IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "삭제할 프로필 이미지가 없습니다."),
    IMAGE_DELETE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이미지 삭제에 실패했습니다."),


    // ===== 매칭 =====

    ALREADY_IN_QUEUE(HttpStatus.CONFLICT, "이미 매칭 대기열에 등록되어 있습니다."),
    NOT_IN_QUEUE(HttpStatus.NOT_FOUND, "매칭 대기열에 등록되어 있지 않습니다."),
    ALREADY_MATCHED(HttpStatus.CONFLICT, "이미 매칭이 성사되었습니다."),

    // ===== 연습 모드 =====

    NO_MORE_QUESTIONS(HttpStatus.NOT_FOUND, "더 이상 풀 문제가 없습니다."),
    ALREADY_ANSWERED(HttpStatus.CONFLICT, "이미 답안을 제출한 문제입니다."),
    SESSION_FINISHED(HttpStatus.CONFLICT, "이미 종료된 연습 세션입니다."),
    PRACTICE_IN_PROGRESS(HttpStatus.CONFLICT, "진행 중인 연습을 먼저 완료하거나 종료해 주세요."),
    QUESTION_UNAVAILABLE(HttpStatus.CONFLICT, "더 이상 풀 수 없는 문제입니다. 다음 문제를 조회해 주세요."),

    // ===== 경기 결과 및 복습 =====

    MATCH_NOT_FOUND(HttpStatus.NOT_FOUND, "경기를 찾을 수 없습니다."),
    MATCH_NOT_ENDED(HttpStatus.CONFLICT, "아직 종료되지 않은 경기입니다."),
    ALREADY_SAVED(HttpStatus.CONFLICT, "이미 복습 목록에 저장한 문제입니다."),

    // ===== 문제 신고 =====

    ALREADY_REPORTED(HttpStatus.CONFLICT, "이미 신고한 문제입니다."),

    // ===== 관리자 =====

    QUESTION_IN_USE(HttpStatus.CONFLICT, "이미 경기에 출제된 문제는 수정할 수 없습니다."),
    ALREADY_PROCESSED(HttpStatus.CONFLICT, "이미 처리된 신고입니다."),
    ALREADY_ROLLED_BACK(HttpStatus.CONFLICT, "이미 레이팅 롤백이 완료된 경기입니다."),
    NOT_RATED_MATCH(HttpStatus.CONFLICT, "레이팅이 반영된 경기가 아닙니다.");

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
