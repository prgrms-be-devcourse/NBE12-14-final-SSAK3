package com.csmatch.question.entity;

import com.csmatch.question.Enum.Difficulty;
import com.csmatch.question.Enum.QuestionSource;
import com.csmatch.question.Enum.QuestionStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "questions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Question {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "question_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id")
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Difficulty difficulty;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String explanation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private QuestionSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private QuestionStatus status;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // MANUAL 문제는 개념이 없을 수 있어 null 허용
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "concept_id")
    private Concept concept;

    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("choiceNo ASC")
    private List<QuestionChoice> choices = new ArrayList<>();

    private Question(Category category, Difficulty difficulty, String content, String explanation,
                     QuestionSource source, Concept concept, Long createdBy) {
        this.category = category;
        this.difficulty = difficulty;
        this.content = content;
        this.explanation = explanation;
        this.source = source;
        this.concept = concept;
        this.createdBy = createdBy;
        this.status = QuestionStatus.DRAFT;
    }

    /** 보기는 정확히 4개, 정답은 정확히 1개여야 한다. choiceNo 는 입력 순서대로 1~4. */
    public static Question create(Category category, Difficulty difficulty, String content,
                                  String explanation, QuestionSource source, Concept concept,
                                  Long createdBy, List<ChoiceInput> inputs) {
        if (inputs == null || inputs.size() != 4) {
            throw new IllegalArgumentException("보기는 정확히 4개여야 합니다.");
        }
        if (inputs.stream().filter(ChoiceInput::correct).count() != 1) {
            throw new IllegalArgumentException("정답은 정확히 1개여야 합니다.");
        }
        Question question = new Question(category, difficulty, content, explanation, source, concept, createdBy);
        for (int i = 0; i < inputs.size(); i++) {
            ChoiceInput input = inputs.get(i);
            question.choices.add(new QuestionChoice(question, i + 1, input.content(), input.correct()));
        }
        return question;
    }

    public record ChoiceInput(String content, boolean correct) {
    }

    public void activate() {
        if (status != QuestionStatus.DRAFT) {
            throw new IllegalStateException("DRAFT 상태의 문제만 활성화할 수 있습니다.");
        }
        status = QuestionStatus.ACTIVE;
    }

    public void hide() {
        status = QuestionStatus.HIDDEN;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
