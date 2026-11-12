package com.csmatch.practice.repository;

import com.csmatch.practice.entity.PracticeSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PracticeSessionRepository extends JpaRepository<PracticeSession, Long> {
    Optional<PracticeSession> findByUserIdAndStatus(Long userId, String status);
    Page<PracticeSession> findByUserIdOrderByStartedAtDescIdDesc(Long userId, Pageable pageable);
}
