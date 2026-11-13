package com.csmatch.practice.entity;

import com.csmatch.question.entity.Question;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

@Entity
@Table(name = "practice_answers", uniqueConstraints = {
        @UniqueConstraint(name = "uk_practice_question", columnNames = {"session_id", "question_id"}),
        @UniqueConstraint(name = "uk_practice_position", columnNames = {"session_id", "question_index"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PracticeAnswer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "answer_id")
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id")
    private PracticeSession session;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id")
    private Question question;
    @Column(name = "question_index", nullable = false)
    private int questionIndex;
    @Column(name = "category_id", nullable = false)
    private Integer categoryId;
    @Column(nullable = false, length = 10)
    private String difficulty;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;
    @Column(columnDefinition = "TEXT")
    private String explanation;
    @ElementCollection
    @CollectionTable(name = "practice_choices", joinColumns = @JoinColumn(name = "answer_id"),
            uniqueConstraints = @UniqueConstraint(name = "uk_practice_choice", columnNames = {"answer_id", "choice_no"}))
    @OrderBy("choiceNo ASC")
    private List<PracticeChoice> choices = new ArrayList<>();
    @Column(name = "selected_choice_id")
    private Long selectedChoiceId;
    private Boolean correct;
    @Column(nullable = false)
    private boolean skipped;
    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    public PracticeAnswer(PracticeSession session, Question question, int index) {
        this(session, question, index, ThreadLocalRandom.current());
    }

    PracticeAnswer(PracticeSession session, Question question, int index, Random random) {
        this.session = session;
        this.question = question;
        questionIndex = index;
        categoryId = question.getCategory().getId();
        difficulty = question.getDifficulty().name();
        content = question.getContent();
        explanation = question.getExplanation();
        // 원본 순서는 보존하고, 연습에 배정할 때만 섞어 표시 순서를 저장한다.
        var shuffled = new ArrayList<>(question.getChoices());
        Collections.shuffle(shuffled, random);
        for (int i = 0; i < shuffled.size(); i++) {
            choices.add(new PracticeChoice(shuffled.get(i), i + 1));
        }
    }

    public boolean isAnswered() {
        return submittedAt != null;
    }

    public boolean isPending() {
        return !isAnswered() && !skipped;
    }

    public void skip() {
        if (isPending()) {
            skipped = true;
        }
    }

    public void submit(PracticeChoice choice, LocalDateTime now) {
        if (!isPending()) {
            throw new IllegalStateException("제출할 수 없는 문제입니다.");
        }
        selectedChoiceId = choice.getChoiceId();
        correct = choice.isCorrect();
        submittedAt = now;
    }

    public Long correctChoiceId() {
        return choices.stream().filter(PracticeChoice::isCorrect).findFirst().orElseThrow().getChoiceId();
    }
}
