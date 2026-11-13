package com.csmatch.question.repository;

import com.csmatch.question.dto.ConceptSource;
import com.csmatch.question.entity.Concept;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ConceptRepository extends JpaRepository<Concept, Integer> {

    /** 연결된 문제가 가장 적은 개념부터, 같으면 무작위로 돌려준다. categoryId 가 null 이면 전체 분야. */
    @Query("""
            select new com.csmatch.question.dto.ConceptSource(c.id, c.name, c.description, c.category.name)
            from Concept c
            where (:categoryId is null or c.category.id = :categoryId)
            order by (select count(q) from Question q where q.concept = c) asc, function('RAND')
            """)
    List<ConceptSource> findLeastCovered(@Param("categoryId") Integer categoryId);
}
