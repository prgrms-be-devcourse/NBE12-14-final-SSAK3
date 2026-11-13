package com.csmatch.question.llm;

/** 검증 결과. 통과하지 못했으면 reason 에 이유, 이유는 저장없이 로그에만 */
public record VerificationResult(boolean passed, String reason) {

    public static VerificationResult pass() {
        return new VerificationResult(true, null);
    }

    public static VerificationResult fail(String reason) {
        return new VerificationResult(false, reason);
    }
}
