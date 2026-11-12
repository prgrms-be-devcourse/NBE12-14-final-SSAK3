package com.csmatch.review.repository;

import com.csmatch.review.entity.SavedQuestion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SavedQuestionRepository extends JpaRepository<SavedQuestion, SavedQuestion.Key> {
    @Query("""
            select s from SavedQuestion s where s.userId = :userId
              and (:categoryId is null or s.question.category.id = :categoryId)
            order by s.savedAt desc, s.questionId desc
            """)
    Page<SavedQuestion> findSaved(@Param("userId") Long userId, @Param("categoryId") Integer categoryId, Pageable pageable);
}
