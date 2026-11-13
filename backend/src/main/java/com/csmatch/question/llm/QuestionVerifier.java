package com.csmatch.question.llm;

/**
 * 생성한 문제를 생성과 별도의 LLM 호출로 검증한다. 생성 호출이 스스로 검토한 결과는 믿지 않는다.
 * 호출은 관리자가 시작한 문제 생성 작업에서만 일어난다.
 */
public interface QuestionVerifier {

    /** 검증에 쓰는 모델. 생성 모델과 따로 정한다. */
    String model();

    /** @throws LlmException 호출이 실패했거나 응답을 쓸 수 없을 때. 문제가 있다는 판정은 예외가 아니라 결과로 돌려준다. */
    VerificationResult verify(VerificationRequest request);
}
