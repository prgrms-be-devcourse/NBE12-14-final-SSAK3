package com.react.ai.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AudienceAnalysisQuestionTypeEvaluatorTest {
    private final AudienceAnalysisQuestionTypeEvaluator evaluator = new AudienceAnalysisQuestionTypeEvaluator();

    @Test
    void 질문_포착률과_질문_호환_category_정확도를_분리한다() {
        var result = evaluator.evaluate(List.of(1L, 2L, 3L), List.of(
                new AudienceAnalysisQuestionTypeEvaluator.ActualTopic("QUESTION", List.of(1L)),
                new AudienceAnalysisQuestionTypeEvaluator.ActualTopic("CONFUSION", List.of(2L)),
                new AudienceAnalysisQuestionTypeEvaluator.ActualTopic("MIXED", List.of(4L))));

        assertThat(result.captureRecall()).isEqualTo(2.0 / 3.0);
        assertThat(result.categoryAccuracyAmongLabelledQuestions()).isEqualTo(1.0 / 3.0);
        assertThat(result.missingQuestionMessageIds()).containsExactly(3L);
    }

    @Test
    void 질문_정답이_없는_시나리오는_성공이_아닌_NA다() {
        var result = evaluator.evaluate(List.of(), List.of(
                new AudienceAnalysisQuestionTypeEvaluator.ActualTopic("QUESTION", List.of(1L))));

        assertThat(result.notApplicable()).isTrue();
        assertThat(result.captureRecall()).isNaN();
        assertThat(result.categoryAccuracyAmongLabelledQuestions()).isNaN();
    }
}
