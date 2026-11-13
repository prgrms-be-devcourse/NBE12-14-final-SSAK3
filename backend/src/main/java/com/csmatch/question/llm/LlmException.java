package com.csmatch.question.llm;

/** LLM 호출 실패. fatal 이면 같은 설정으로는 다시 해도 소용없으므로(API 키 오류 등) 생성 작업 전체를 멈춘다. */
public class LlmException extends RuntimeException {

    private final boolean fatal;

    public LlmException(String message, boolean fatal, Throwable cause) {
        super(message, cause);
        this.fatal = fatal;
    }

    public LlmException(String message) {
        this(message, false, null);
    }

    public boolean isFatal() {
        return fatal;
    }
}
