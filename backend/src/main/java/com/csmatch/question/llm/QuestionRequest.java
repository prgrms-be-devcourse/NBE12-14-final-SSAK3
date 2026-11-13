package com.csmatch.question.llm;

import com.csmatch.question.Enum.Difficulty;

import java.util.List;

/** 문제 하나를 만들기 위해 LLM 에 넘기는 입력. 엔티티 대신 값만 담아 세션 밖에서도 안전하게 쓴다. */
public record QuestionRequest(
        String categoryName,
        String conceptName,
        String conceptDescription,
        Difficulty difficulty,
        List<String> existingQuestions) {
}
