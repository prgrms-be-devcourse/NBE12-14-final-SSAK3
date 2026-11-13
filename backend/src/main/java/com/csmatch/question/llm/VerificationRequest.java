package com.csmatch.question.llm;

/** 검증할 문제와 그 문제의 근거가 된 개념. 문제는 서버 검증(validate)을 통과한 것이어야 한다. */
public record VerificationRequest(
        String conceptName,
        String conceptDescription,
        GeneratedQuestion question) {
}
