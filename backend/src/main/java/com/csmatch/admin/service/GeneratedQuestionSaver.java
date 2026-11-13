package com.csmatch.admin.service;

import com.csmatch.question.Enum.Difficulty;
import com.csmatch.question.Enum.QuestionSource;
import com.csmatch.question.entity.Concept;
import com.csmatch.question.entity.Question;
import com.csmatch.question.llm.GeneratedQuestion;
import com.csmatch.question.repository.ConceptRepository;
import com.csmatch.question.repository.QuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 생성한 문제 하나를 따로 커밋한다. 서버가 중간에 꺼져도 이미 저장한 문제는 남는다. */
@Component
@RequiredArgsConstructor
public class GeneratedQuestionSaver {
    private final ConceptRepository concepts;
    private final QuestionRepository questions;

    /** 정답 위치가 한쪽으로 몰리지 않게 보기 순서를 섞어 DRAFT, AI 문제로 저장한다. */
    @Transactional
    public Long save(Integer conceptId, Difficulty difficulty, GeneratedQuestion generated) {
        Concept concept = concepts.findById(conceptId)
                .orElseThrow(() -> new IllegalStateException("개념을 찾을 수 없습니다: " + conceptId));
        List<Question.ChoiceInput> inputs = new ArrayList<>(generated.choices().stream()
                .map(choice -> new Question.ChoiceInput(choice.content().trim(), choice.correct()))
                .toList());
        Collections.shuffle(inputs);
        Question question = Question.create(concept.getCategory(), difficulty,
                generated.content().trim(), generated.explanation().trim(),
                QuestionSource.AI, concept, null, inputs);
        return questions.save(question).getId();
    }
}
