package com.csmatch.practice.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "practice_progress")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PracticeProgress {
    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "learning_cycle", nullable = false)
    private int learningCycle;

    public PracticeProgress(Long userId) {
        this.userId = userId;
        this.learningCycle = 1;
    }

    public void restart() {
        learningCycle = Math.incrementExact(learningCycle);
    }
}
