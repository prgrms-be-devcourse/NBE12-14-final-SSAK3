package com.react.ai.exception;

/**
 * DB에 보관된 배치 manifest 또는 결과가 더 이상 안전하게 복원될 수 없음을 나타낸다.
 * 원본 JSON이나 예외 메시지는 로그·상태 코드로 전달하지 않는다.
 */
public final class InvalidStoredAiBatchDataException extends RuntimeException {
    public static final String STORED_BATCH_DATA_INVALID = "STORED_BATCH_DATA_INVALID";
    public static final String INPUT_MANIFEST_MISMATCH = "INPUT_MANIFEST_MISMATCH";

    private final String errorCode;

    private InvalidStoredAiBatchDataException(String errorCode, Throwable cause) {
        super("저장된 AI 배치 데이터를 복원할 수 없습니다.", cause);
        this.errorCode = errorCode;
    }

    public static InvalidStoredAiBatchDataException unreadableJson(Throwable cause) {
        return new InvalidStoredAiBatchDataException(STORED_BATCH_DATA_INVALID, cause);
    }

    public static InvalidStoredAiBatchDataException manifestMismatch() {
        return new InvalidStoredAiBatchDataException(INPUT_MANIFEST_MISMATCH, null);
    }

    public String errorCode() {
        return errorCode;
    }
}
