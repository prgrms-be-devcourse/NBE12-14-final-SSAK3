package com.csmatch.question.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "question_choices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuestionChoice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "choice_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id")
    private Question question;

    @Column(name = "choice_no", nullable = false, columnDefinition = "TINYINT")
    private int choiceNo;

    @Column(nullable = false, length = 500)
    private String content;

    @Column(name = "is_correct", nullable = false)
    private boolean correct;

    QuestionChoice(Question question, int choiceNo, String content, boolean correct) {
        this.question = question;
        this.choiceNo = choiceNo;
        this.content = content;
        this.correct = correct;
    }
}
