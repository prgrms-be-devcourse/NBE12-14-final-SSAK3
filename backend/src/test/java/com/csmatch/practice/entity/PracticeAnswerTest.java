package com.csmatch.practice.entity;

import com.csmatch.question.Enum.Difficulty;
import com.csmatch.question.Enum.QuestionSource;
import com.csmatch.question.entity.Category;
import com.csmatch.question.entity.Question;
import com.csmatch.question.entity.QuestionChoice;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class PracticeAnswerTest {
    @Test
    void shuffledChoicesPreserveIdentityContentAndGradingWithoutChangingOriginal() {
        Question question = question();
        var originals = List.copyOf(question.getChoices());
        Random random = new Random() {
            @Override
            public int nextInt(int bound) {
                return 0;
            }
        };
        var answer = new PracticeAnswer(null, question, 1, random);

        assertThat(answer.getChoices()).extracting(PracticeChoice::getChoiceId)
                .containsExactlyInAnyOrder(101L, 102L, 103L, 104L);
        assertThat(answer.getChoices()).extracting(PracticeChoice::getChoiceNo).containsExactly(1, 2, 3, 4);
        assertThat(answer.getChoices()).extracting(PracticeChoice::getChoiceId)
                .isNotEqualTo(originals.stream().map(QuestionChoice::getId).toList());
        for (var snapshot : answer.getChoices()) {
            var original = originals.stream().filter(choice -> choice.getId().equals(snapshot.getChoiceId())).findFirst().orElseThrow();
            assertThat(snapshot.getContent()).isEqualTo(original.getContent());
            assertThat(snapshot.isCorrect()).isEqualTo(original.isCorrect());
        }
        assertThat(question.getChoices()).extracting(QuestionChoice::getId).containsExactly(101L, 102L, 103L, 104L);
        assertThat(question.getChoices()).extracting(QuestionChoice::getChoiceNo).containsExactly(1, 2, 3, 4);
        var correct = answer.getChoices().stream().filter(PracticeChoice::isCorrect).findFirst().orElseThrow();
        assertThat(correct.getChoiceNo()).isNotEqualTo(1);
        answer.submit(correct, LocalDateTime.of(2026, 11, 13, 12, 0));
        assertThat(answer.getCorrect()).isTrue();
        assertThat(answer.getSelectedChoiceId()).isEqualTo(101L);
        assertThat(answer.correctChoiceId()).isEqualTo(101L);
    }

    @Test
    void shuffleMayProduceOriginalOrder() {
        Random random = new Random() {
            @Override
            public int nextInt(int bound) {
                return bound - 1;
            }
        };
        var answer = new PracticeAnswer(null, question(), 1, random);
        assertThat(answer.getChoices()).extracting(PracticeChoice::getChoiceId).containsExactly(101L, 102L, 103L, 104L);
    }

    private Question question() {
        var category = BeanUtils.instantiateClass(Category.class);
        ReflectionTestUtils.setField(category, "id", 1);
        var question = Question.create(category, Difficulty.EASY, "문제", "해설", QuestionSource.MANUAL,
                null, null, List.of(new Question.ChoiceInput("정답", true), new Question.ChoiceInput("오답1", false),
                        new Question.ChoiceInput("오답2", false), new Question.ChoiceInput("오답3", false)));
        for (int i = 0; i < 4; i++) {
            ReflectionTestUtils.setField(question.getChoices().get(i), "id", 101L + i);
        }
        return question;
    }
}
