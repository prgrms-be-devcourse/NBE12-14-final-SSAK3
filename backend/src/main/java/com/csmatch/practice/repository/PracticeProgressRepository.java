package com.csmatch.practice.repository;

import com.csmatch.practice.entity.PracticeProgress;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PracticeProgressRepository extends JpaRepository<PracticeProgress, Long> {
}
