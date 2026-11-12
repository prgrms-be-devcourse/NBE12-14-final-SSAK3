package com.csmatch.review.entity;

import com.csmatch.question.entity.Question;
import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;

@Entity
@Table(name = "saved_questions")
@IdClass(SavedQuestion.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedQuestion {
    @Id
    @Column(name = "user_id")
    private Long userId;
    @Id
    @Column(name = "question_id")
    private Long questionId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", insertable = false, updatable = false)
    private Question question;
    @Column(name = "match_id")
    private Long matchId;
    @Column(name = "practice_session_id")
    private Long practiceSessionId;
    @Column(name = "saved_at", nullable = false)
    private LocalDateTime savedAt;

    public SavedQuestion(Long userId, Question question, Long matchId, Long practiceSessionId, LocalDateTime savedAt) {
        this.userId = userId;
        this.questionId = question.getId();
        this.question = question;
        this.matchId = matchId;
        this.practiceSessionId = practiceSessionId;
        this.savedAt = savedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private Long userId;
        private Long questionId;
    }
}
