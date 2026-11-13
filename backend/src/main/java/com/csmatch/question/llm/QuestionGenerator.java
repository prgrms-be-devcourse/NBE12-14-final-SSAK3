package com.csmatch.question.llm;

/**
 * 개념으로 문제 하나를 만든다. 구현을 바꿔 끼울 수 있게 인터페이스로 둔다(예: Batch API 구현).
 * 호출은 관리자가 시작한 문제 생성 작업에서만 일어난다.
 */
public interface QuestionGenerator {

    /** 요청이 모델을 지정하지 않을 때 쓰는 모델. */
    String defaultModel();

    /** API 키 등 호출에 필요한 설정이 있는지. 없으면 생성 API 는 503 을 돌려준다. */
    boolean isConfigured();

    /** @throws LlmException 호출이 실패했거나 응답을 쓸 수 없을 때 */
    GeneratedQuestion generate(QuestionRequest request);
}
