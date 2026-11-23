package com.react.ai.service;

import com.react.ai.config.AudienceAnalysisCostProperties;
import com.react.ai.dto.AudienceAnalysisLlmResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiLlmCostCalculatorTest {
    private final AiLlmCostCalculator calculator = new AiLlmCostCalculator();

    @Test
    void 실제_토큰_사용량과_설정_단가로_마이크로달러_추정치를_계산한다() {
        var estimate = calculator.estimate(new AudienceAnalysisLlmResponse.Usage(120L, 80L, 200L),
                new AudienceAnalysisCostProperties("0.25", "1.50"));

        assertThat(estimate).isPresent();
        assertThat(estimate.orElseThrow().estimatedCostMicrousd()).isEqualTo(150L);
        assertThat(estimate.orElseThrow().inputUsdPerMillionTokens()).hasToString("0.25");
        assertThat(estimate.orElseThrow().outputUsdPerMillionTokens()).hasToString("1.50");
    }

    @Test
    void 사용량이나_단가가_없으면_추정_비용을_만들지_않는다() {
        assertThat(calculator.estimate(new AudienceAnalysisLlmResponse.Usage(null, 80L, null),
                new AudienceAnalysisCostProperties("0.25", "1.50"))).isEmpty();
        assertThat(calculator.estimate(new AudienceAnalysisLlmResponse.Usage(120L, 80L, 200L),
                new AudienceAnalysisCostProperties(null, null))).isEmpty();
    }

    @Test
    void 전체_토큰이_입력과_출력_토큰의_합계와_다르면_과소_추정하지_않는다() {
        assertThat(calculator.estimate(new AudienceAnalysisLlmResponse.Usage(120L, 80L, 230L),
                new AudienceAnalysisCostProperties("0.25", "1.50"))).isEmpty();
    }
}
