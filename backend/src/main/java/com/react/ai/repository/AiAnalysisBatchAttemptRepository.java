package com.react.ai.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Gemini 호출 전에 분석 전체에서 고유한 호출 이력 번호를 예약한다. */
@Repository
@RequiredArgsConstructor
public class AiAnalysisBatchAttemptRepository {
    private final JdbcClient jdbc;

    @Transactional
    public int reserve(long analysisId) {
        Integer reserved = jdbc.sql("""
                SELECT next_batch_attempt_number FROM ai_analyses
                WHERE id = ? AND status = 'PROCESSING' FOR UPDATE
                """).param(analysisId).query(Integer.class).optional()
                .orElseThrow(() -> new IllegalStateException("처리 중인 분석을 찾을 수 없습니다."));
        int lastRecorded = jdbc.sql("""
                SELECT COALESCE(MAX(attempt_number), 0) FROM ai_llm_call_usages WHERE analysis_id = ?
                """).param(analysisId).query(Integer.class).single();
        int next = Math.max(reserved, Math.addExact(lastRecorded, 1));
        jdbc.sql("UPDATE ai_analyses SET next_batch_attempt_number = ? WHERE id = ?")
                .params(Math.addExact(next, 1), analysisId).update();
        return next;
    }
}
