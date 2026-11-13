package com.csmatch.question.repository;

import com.csmatch.question.entity.Question;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface QuestionRepository extends JpaRepository<Question, Long> {
    /** 같은 개념에 이미 있는 문제 본문. 새 문제가 겹치지 않게 프롬프트에 넘긴다. */
    @Query("select q.content from Question q where q.concept.id = :conceptId order by q.id desc")
    List<String> findContentsByConceptId(@Param("conceptId") Integer conceptId, Pageable pageable);

    @Query("""
            select q from Question q
            where q.status = com.csmatch.question.Enum.QuestionStatus.ACTIVE
              and (:categoryId is null or q.category.id = :categoryId)
              and not exists (select a.id from PracticeAnswer a
                  where a.question = q and a.session.userId = :userId
                    and a.session.learningCycle = :cycle and a.submittedAt is not null)
            order by function('RAND')
            """)
    List<Question> findUnanswered(@Param("userId") Long userId, @Param("cycle") int cycle,
                                  @Param("categoryId") Integer categoryId, Pageable pageable);

    @Query("""
            select count(q) from Question q
            where q.status = com.csmatch.question.Enum.QuestionStatus.ACTIVE
              and (:categoryId is null or q.category.id = :categoryId)
            """)
    long countAvailable(@Param("categoryId") Integer categoryId);

    @Query("""
            select count(q) from Question q
            where q.status = com.csmatch.question.Enum.QuestionStatus.ACTIVE
              and (:categoryId is null or q.category.id = :categoryId)
              and exists (select a.id from PracticeAnswer a
                  where a.question = q and a.session.userId = :userId
                    and a.session.learningCycle = :cycle and a.submittedAt is not null)
            """)
    long countAnswered(@Param("userId") Long userId, @Param("cycle") int cycle,
                       @Param("categoryId") Integer categoryId);
}
