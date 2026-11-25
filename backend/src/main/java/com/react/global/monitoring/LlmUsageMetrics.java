package com.react.global.monitoring;

import com.react.ai.domain.AiLlmCallStatus;
import com.react.ai.dto.AiLlmCallUsage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Gemini 청중 분석 호출 이력과 토큰 사용량을 기록한다.
 * 계측 실패는 기존 분석 작업에 영향을 주지 않는다.
 */
@Slf4j
@Component
public class LlmUsageMetrics {

    private final Counter successfulCalls;
    private final Counter failedCalls;
    private final Counter inputTokens;
    private final Counter outputTokens;
    private final Counter totalTokens;
    private final Counter estimatedCost;

    public LlmUsageMetrics(MeterRegistry registry) {
        this.successfulCalls = Counter.builder("react.llm.calls")
                .tag("status", "success")
                .register(registry);

        this.failedCalls = Counter.builder("react.llm.calls")
                .tag("status", "failed")
                .register(registry);

        this.inputTokens = Counter.builder("react.llm.tokens.input")
                .register(registry);

        this.outputTokens = Counter.builder("react.llm.tokens.output")
                .register(registry);

        this.totalTokens = Counter.builder("react.llm.tokens.total")
                .register(registry);

        this.estimatedCost = Counter.builder("react.llm.cost.estimated.microusd")
                .register(registry);
    }

    public void record(AiLlmCallUsage usage) {
        try {
            if (usage.callStatus() == AiLlmCallStatus.SUCCESS) {
                successfulCalls.increment();
            } else {
                failedCalls.increment();
            }

            increment(inputTokens, usage.promptTokenCount());
            increment(outputTokens, usage.outputTokenCount());
            increment(totalTokens, usage.totalTokenCount());
            increment(estimatedCost, usage.estimatedCostMicrousd());

        } catch (RuntimeException e) {
            log.debug("LLM 사용량 메트릭 기록에 실패했습니다.", e);
        }
    }

    private void increment(Counter counter, Long amount) {
        if (amount != null && amount > 0) {
            counter.increment(amount);
        }
    }
}
