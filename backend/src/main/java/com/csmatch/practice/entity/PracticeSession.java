package com.csmatch.practice.entity;

import com.csmatch.question.Enum.QuestionStatus;
import com.csmatch.question.entity.Question;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "practice_sessions", indexes = @Index(name = "ix_practice_user_status", columnList = "user_id,status"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PracticeSession {
    public enum Status { IN_PROGRESS, COMPLETED, ABANDONED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "session_id")
    private Long id;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "category_id")
    private Integer categoryId;
    @Column(name = "learning_cycle", nullable = false)
    private int learningCycle;
    @Column(name = "question_count", nullable = false)
    private int questionCount;
    @Column(name = "correct_count", nullable = false)
    private int correctCount;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;
    @Column(name = "ended_at")
    private LocalDateTime endedAt;
    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL)
    @OrderBy("questionIndex ASC")
    private List<PracticeAnswer> answers = new ArrayList<>();

    public PracticeSession(Long userId, Integer categoryId, int cycle, List<Question> questions, LocalDateTime now) {
        this.userId = userId;
        this.categoryId = categoryId;
        learningCycle = cycle;
        questionCount = questions.size();
        status = Status.IN_PROGRESS.name();
        startedAt = now;
        for (int i = 0; i < questions.size(); i++) {
            answers.add(new PracticeAnswer(this, questions.get(i), i + 1));
        }
    }

    public boolean isInProgress() {
        return Status.IN_PROGRESS.name().equals(status);
    }

    public void reconcileHidden(LocalDateTime now) {
        if (!isInProgress()) {
            return;
        }
        for (PracticeAnswer answer : answers) {
            if (answer.isPending() && answer.getQuestion().getStatus() != QuestionStatus.ACTIVE) {
                answer.skip();
                questionCount--;
            }
        }
        completeIfDone(now);
    }

    public void recordAnswer(PracticeAnswer answer, PracticeChoice choice, LocalDateTime now) {
        answer.submit(choice, now);
        if (choice.isCorrect()) {
            correctCount++;
        }
        completeIfDone(now);
    }

    private void completeIfDone(LocalDateTime now) {
        if (answers.stream().noneMatch(PracticeAnswer::isPending)) {
            status = Status.COMPLETED.name();
            endedAt = now;
        }
    }

    public void abandon(LocalDateTime now) {
        status = Status.ABANDONED.name();
        endedAt = now;
    }
}
