package com.react.ai.exception;

/** 외부 Gemini 호출 설정·전송·응답 처리 실패를 나타낸다. 민감한 요청 내용은 포함하지 않는다. */
public class GeminiAudienceAnalysisException extends RuntimeException {
    public GeminiAudienceAnalysisException(String message) {
        super(message);
    }

}
