package com.react.ai.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AudienceAnalysisClusteringEvaluatorTest {
    private final AudienceAnalysisClusteringEvaluator evaluator = new AudienceAnalysisClusteringEvaluator();

    @Test
    void 동일한_그룹은_pairwise_precision_recall_f1이_모두_1이다() {
        AudienceAnalysisClusteringEvaluator.ClusterEvaluation result = evaluator.evaluate(
                List.of(1L, 2L, 3L, 4L),
                List.of(List.of(1L, 2L), List.of(3L, 4L)),
                List.of(List.of(1L, 2L), List.of(3L, 4L))
        );

        assertThat(result.precision()).isEqualTo(1.0);
        assertThat(result.recall()).isEqualTo(1.0);
        assertThat(result.f1()).isEqualTo(1.0);
        assertThat(result.incorrectlyGroupedPairs()).isEmpty();
        assertThat(result.splitPairs()).isEmpty();
    }

    @Test
    void 하나의_기대_그룹을_분리하면_분리된_메시지_쌍과_recall을_계산한다() {
        AudienceAnalysisClusteringEvaluator.ClusterEvaluation result = evaluator.evaluate(
                List.of(1001L, 1002L, 1003L, 1004L),
                List.of(List.of(1001L, 1002L, 1003L, 1004L)),
                List.of(List.of(1001L, 1002L, 1003L), List.of(1004L))
        );

        assertThat(result.precision()).isEqualTo(1.0);
        assertThat(result.recall()).isEqualTo(0.5);
        assertThat(result.f1()).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(result.incorrectlyGroupedPairs()).isEmpty();
        assertThat(result.splitPairs()).containsExactlyInAnyOrder(
                AudienceAnalysisClusteringEvaluator.MessagePair.of(1001L, 1004L),
                AudienceAnalysisClusteringEvaluator.MessagePair.of(1002L, 1004L),
                AudienceAnalysisClusteringEvaluator.MessagePair.of(1003L, 1004L)
        );
    }

    @Test
    void 서로_다른_기대_그룹을_합치면_잘못_묶인_메시지_쌍과_precision을_계산한다() {
        AudienceAnalysisClusteringEvaluator.ClusterEvaluation result = evaluator.evaluate(
                List.of(1L, 2L, 3L, 4L),
                List.of(List.of(1L, 2L), List.of(3L, 4L)),
                List.of(List.of(1L, 2L, 3L, 4L))
        );

        assertThat(result.precision()).isCloseTo(1.0 / 3.0, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(result.recall()).isEqualTo(1.0);
        assertThat(result.f1()).isEqualTo(0.5);
        assertThat(result.incorrectlyGroupedPairs()).containsExactlyInAnyOrder(
                AudienceAnalysisClusteringEvaluator.MessagePair.of(1L, 3L),
                AudienceAnalysisClusteringEvaluator.MessagePair.of(1L, 4L),
                AudienceAnalysisClusteringEvaluator.MessagePair.of(2L, 3L),
                AudienceAnalysisClusteringEvaluator.MessagePair.of(2L, 4L)
        );
        assertThat(result.splitPairs()).isEmpty();
    }

    @Test
    void 그룹안이나_그룹사이에_메시지_ID가_중복되면_거부한다() {
        assertThatIllegalArgumentException().isThrownBy(() -> evaluator.evaluate(
                List.of(1L, 2L),
                List.of(List.of(1L, 2L)),
                List.of(List.of(1L, 1L))
        )).withMessageContaining("중복된 메시지 ID");

        assertThatIllegalArgumentException().isThrownBy(() -> evaluator.evaluate(
                List.of(1L, 2L, 3L),
                List.of(List.of(1L, 2L), List.of(3L)),
                List.of(List.of(1L, 2L), List.of(2L, 3L))
        )).withMessageContaining("중복된 메시지 ID");
    }

    @Test
    void 입력_메시지에_없는_ID는_거부한다() {
        assertThatIllegalArgumentException().isThrownBy(() -> evaluator.evaluate(
                List.of(1L, 2L),
                List.of(List.of(1L, 2L)),
                List.of(List.of(1L, 3L))
        )).withMessageContaining("입력 메시지에 없는 ID");
    }

    @Test
    void 빈_그룹은_메시지_쌍을_만들지_않으며_양쪽이_비면_완전_일치로_처리한다() {
        AudienceAnalysisClusteringEvaluator.ClusterEvaluation result = evaluator.evaluate(
                List.of(1L, 2L),
                List.of(List.<Long>of()),
                List.of(List.<Long>of())
        );

        assertThat(result.expectedPairs()).isEmpty();
        assertThat(result.actualPairs()).isEmpty();
        assertThat(result.precision()).isEqualTo(1.0);
        assertThat(result.recall()).isEqualTo(1.0);
        assertThat(result.f1()).isEqualTo(1.0);
    }

    @Test
    void 핵심_메시지_누락과_허용되지_않은_category를_자동_검증하고_입장과_금지주장_검토항목을_보존한다() {
        var expectedCluster = new AudienceAnalysisClusteringEvaluator.ExpectedCluster(
                "architecture-choice",
                List.of(1L, 2L, 3L),
                Set.of("QUESTION", "MIXED"),
                List.of(new AudienceAnalysisClusteringEvaluator.RequiredStance(
                        List.of(1L), "도입을 지지하는 의견"))
        );
        var actualTopic = new AudienceAnalysisClusteringEvaluator.ActualTopic(
                "아키텍처 선택", "OPINION", "도입 의견이 있었습니다.", List.of(1L, 2L));

        AudienceAnalysisClusteringEvaluator.QualityEvaluation result = evaluator.evaluateQuality(
                List.of(1L, 2L, 3L),
                List.of(expectedCluster),
                List.of(actualTopic),
                List.of("청중이 도입에 합의했다.")
        );

        assertThat(result.allMustCoverMessagesIncluded()).isFalse();
        assertThat(result.missingMustCoverMessageIds()).containsExactly(3L);
        assertThat(result.categoriesAllowed()).isFalse();
        assertThat(result.categoryViolations()).singleElement().satisfies(violation -> {
            assertThat(violation.actualCategory()).isEqualTo("OPINION");
            assertThat(violation.expectedClusterId()).isEqualTo("architecture-choice");
            assertThat(violation.allowedCategories()).containsExactlyInAnyOrder("QUESTION", "MIXED");
        });
        assertThat(result.stanceReviewItems()).singleElement().satisfies(item -> {
            assertThat(item.requiredStance().meaning()).isEqualTo("도입을 지지하는 의견");
            assertThat(item.actualTopicReferences()).singleElement()
                    .extracting(AudienceAnalysisClusteringEvaluator.ActualTopicReference::summary)
                    .isEqualTo("도입 의견이 있었습니다.");
        });
        assertThat(result.mustNotClaimsForHumanReview()).containsExactly("청중이 도입에 합의했다.");
    }

    @Test
    void 핵심_메시지가_모두_포함되고_category가_허용되면_자동_검증을_통과한다() {
        AudienceAnalysisClusteringEvaluator.QualityEvaluation result = evaluator.evaluateQuality(
                List.of(1L, 2L),
                List.of(new AudienceAnalysisClusteringEvaluator.ExpectedCluster(
                        "redis", List.of(1L, 2L), Set.of("MIXED"), List.of())),
                List.of(new AudienceAnalysisClusteringEvaluator.ActualTopic(
                        "Redis 도입", "MIXED", "찬반 의견이 있습니다.", List.of(1L, 2L))),
                List.of()
        );

        assertThat(result.allMustCoverMessagesIncluded()).isTrue();
        assertThat(result.categoriesAllowed()).isTrue();
        assertThat(result.categoryViolations()).isEmpty();
    }
}
