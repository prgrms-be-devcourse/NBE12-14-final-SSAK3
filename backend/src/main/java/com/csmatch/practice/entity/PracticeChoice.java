package com.csmatch.practice.entity;

import com.csmatch.question.entity.QuestionChoice;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PracticeChoice {
    // 원본 보기가 수정·삭제되어도 출제 당시 보기를 보존한다.
    @Column(name = "choice_id", nullable = false)
    private Long choiceId;
    @Column(name = "choice_no", nullable = false)
    private int choiceNo;
    @Column(nullable = false, length = 500)
    private String content;
    @Column(name = "is_correct", nullable = false)
    private boolean correct;

    public PracticeChoice(QuestionChoice choice, int choiceNo) {
        choiceId = choice.getId();
        this.choiceNo = choiceNo;
        content = choice.getContent();
        correct = choice.isCorrect();
    }
}
