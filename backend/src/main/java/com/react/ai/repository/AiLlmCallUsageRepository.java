package com.react.ai.repository;

import com.react.ai.dto.AiLlmCallUsage;
import com.react.global.monitoring.LlmUsageMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AiLlmCallUsageRepository {
    private final JdbcClient jdbc;
    private final LlmUsageMetrics metrics;

    public void save(AiLlmCallUsage usage) {
        jdbc.sql("""
                INSERT INTO ai_llm_call_usages(
                    analysis_id, attempt_number, model_name, call_status, error_code,
                    prompt_token_count, output_token_count, total_token_count, duration_ms,
                    input_usd_per_million_tokens, output_usd_per_million_tokens,
                    estimated_cost_microusd, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """).params(
                usage.analysisId(), usage.attemptNumber(), usage.modelName(), usage.callStatus().name(),
                usage.errorCode(), usage.promptTokenCount(), usage.outputTokenCount(), usage.totalTokenCount(),
                usage.durationMs(), usage.inputUsdPerMillionTokens(), usage.outputUsdPerMillionTokens(),
                usage.estimatedCostMicrousd()
        ).update();

        // SCRUM-91: 저장된 LLM 호출 이력 및 토큰 사용량을 기록한다.
        metrics.record(usage);
    }
}
