package com.react.ai.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.ai.config.GeminiAudienceAnalysisProperties;
import com.react.ai.dto.AiAnalysisResult;
import com.react.ai.dto.AudienceAnalysisLlmResponse;
import com.react.ai.dto.AudienceMessage;
import com.react.ai.dto.AiTopicResult;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Gemini 품질 확인용 수동 테스트다. 기본 Gradle build에서는 실행하지 않는다.
 * 실행: RUN_GEMINI_MANUAL_TEST=true ./gradlew test --tests com.react.ai.service.GeminiAudienceAnalysisManualQualityTest
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.task.scheduling.enabled=false"
        }
)
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "RUN_GEMINI_MANUAL_TEST", matches = "true")
class GeminiAudienceAnalysisManualQualityTest {
    @Autowired AudienceAnalysisLlmClient llmClient;
    @Autowired AiAnalysisJsonParser jsonParser;
    @Autowired GeminiAudienceAnalysisProperties properties;
    @Autowired ObjectMapper objectMapper;

    private final AudienceAnalysisClusteringEvaluator clusteringEvaluator = new AudienceAnalysisClusteringEvaluator();

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "opposing-architecture-opinions",
            "mixed-question-opinion-request",
            "boundary-multiple-topics-opposition-and-injection"
    })
    void 시나리오를_실제_Gemini로_분석한다(String scenarioId) throws Exception {
        analyzeScenario(scenarioId, null);
    }

    @RepeatedTest(value = 3, name = "mixed-question-opinion-request 반복 {currentRepetition}/{totalRepetitions}")
    void mixedQuestionOpinionRequestRepeated(RepetitionInfo repetitionInfo) throws Exception {
        analyzeScenario("mixed-question-opinion-request",
                "repeat=" + repetitionInfo.getCurrentRepetition() + "/" + repetitionInfo.getTotalRepetitions());
    }

    @RepeatedTest(value = 3, name = "boundary-multiple-topics-opposition-and-injection 반복 {currentRepetition}/{totalRepetitions}")
    void boundaryMultipleTopicsOppositionAndInjectionRepeated(RepetitionInfo repetitionInfo) throws Exception {
        analyzeScenario("boundary-multiple-topics-opposition-and-injection",
                "repeat=" + repetitionInfo.getCurrentRepetition() + "/" + repetitionInfo.getTotalRepetitions());
    }

    private void analyzeScenario(String scenarioId, String repeatLabel) throws Exception {
        assertThat(properties.apiKey()).as("GEMINI_API_KEY 설정").isNotBlank();
        assertThat(properties.model()).as("GEMINI_MODEL 설정").isNotBlank();
        System.out.println("Gemini manual quality test model=" + properties.model());
        System.out.println("GEMINI_API_KEY present=true");

        Scenario scenario = scenario(scenarioId);
        System.out.println("scenario=" + scenario.scenarioId());
        if (repeatLabel != null) {
            System.out.println(repeatLabel);
        }
        List<AudienceMessage> messages = scenario.messages().stream()
                .map(message -> new AudienceMessage(
                        message.id(), 1L, message.content(), LocalDateTime.of(2026, 11, 19, 10, 0)))
                .toList();

        AudienceAnalysisLlmResponse response = llmClient.analyze(messages);
        AiAnalysisResult result = jsonParser.parse(response.content());

        assertThat(result.topics()).as("분석 결과에는 하나 이상의 주제가 있어야 한다").isNotEmpty();
        System.out.println("HTTP request=success (2xx response inferred from RestClient completion)");
        System.out.println("Gemini finishReason=STOP (GeminiAudienceAnalysisLlmClient acceptance condition)");
        System.out.println("JSON parsing=success");
        printTopics(result.topics());
        AudienceAnalysisClusteringEvaluator.QualityEvaluation qualityEvaluation = printClusteringEvaluation(
                scenario, result.topics());
        assertThat(qualityEvaluation.allMustCoverMessagesIncluded())
                .as("핵심 메시지 누락: %s", qualityEvaluation.missingMustCoverMessageIds())
                .isTrue();
        assertThat(qualityEvaluation.categoriesAllowed())
                .as("허용되지 않은 category: %s", qualityEvaluation.categoryViolations())
                .isTrue();
    }

    private Scenario scenario(String scenarioId) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/ai/quality/gemini-audience-analysis-cases.json")) {
            assertThat(input).as("Gemini 품질 시나리오 fixture").isNotNull();
            Dataset dataset = objectMapper.readValue(input, Dataset.class);
            return dataset.scenarios().stream()
                    .filter(scenario -> scenarioId.equals(scenario.scenarioId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("품질 시나리오를 찾을 수 없습니다: " + scenarioId));
        }
    }

    private void printTopics(List<AiTopicResult> topics) {
        for (int index = 0; index < topics.size(); index++) {
            AiTopicResult topic = topics.get(index);
            System.out.printf(
                    "topic[%d] topic=%s, category=%s, summary=%s, sourceMessageIds=%s%n",
                    index,
                    topic.topic(),
                    topic.category(),
                    topic.summary(),
                    topic.sourceMessageIds()
            );
        }
    }

    private AudienceAnalysisClusteringEvaluator.QualityEvaluation printClusteringEvaluation(
            Scenario scenario, List<AiTopicResult> actualTopics) {
        List<List<Long>> expectedGroups = scenario.expectedClusters().stream()
                .map(ExpectedCluster::messageIds)
                .toList();
        List<List<Long>> actualGroups = actualTopics.stream()
                .map(AiTopicResult::sourceMessageIds)
                .toList();
        Set<Long> knownMessageIds = scenario.messages().stream()
                .map(ScenarioMessage::id)
                .collect(java.util.stream.Collectors.toSet());

        AudienceAnalysisClusteringEvaluator.ClusterEvaluation evaluation = clusteringEvaluator.evaluate(
                knownMessageIds, expectedGroups, actualGroups);

        System.out.println("expectedGroups=" + expectedGroups);
        System.out.println("actualGroups=" + actualGroups);
        System.out.printf("pairwise precision=%.3f, recall=%.3f, f1=%.3f%n",
                evaluation.precision(), evaluation.recall(), evaluation.f1());
        System.out.println("incorrectlyGroupedPairs=" + evaluation.incorrectlyGroupedPairs());
        System.out.println("splitPairs=" + evaluation.splitPairs());

        AudienceAnalysisClusteringEvaluator.QualityEvaluation qualityEvaluation = clusteringEvaluator.evaluateQuality(
                scenario.mustCoverMessageIds(),
                scenario.expectedClusters().stream().map(cluster -> new AudienceAnalysisClusteringEvaluator.ExpectedCluster(
                        cluster.clusterId(),
                        cluster.messageIds(),
                        Set.copyOf(cluster.allowedCategories()),
                        cluster.requiredStances().stream()
                                .map(stance -> new AudienceAnalysisClusteringEvaluator.RequiredStance(
                                        stance.messageIds(), stance.meaning()))
                                .toList()
                )).toList(),
                actualTopics.stream().map(topic -> new AudienceAnalysisClusteringEvaluator.ActualTopic(
                        topic.topic(), topic.category().name(), topic.summary(), topic.sourceMessageIds()
                )).toList(),
                scenario.mustNotClaim()
        );

        System.out.println("mustCoverMessageIds: " + (qualityEvaluation.allMustCoverMessagesIncluded()
                ? "PASS" : "MISSING " + qualityEvaluation.missingMustCoverMessageIds()));
        System.out.println("allowedCategories: " + (qualityEvaluation.categoriesAllowed()
                ? "PASS" : "VIOLATIONS " + qualityEvaluation.categoryViolations()));
        printStanceReviewItems(qualityEvaluation.stanceReviewItems());
        printMustNotClaimReviewItems(qualityEvaluation.mustNotClaimsForHumanReview());
        return qualityEvaluation;
    }

    private void printStanceReviewItems(List<AudienceAnalysisClusteringEvaluator.StanceReviewItem> reviewItems) {
        if (reviewItems.isEmpty()) {
            System.out.println("stanceReview: none");
            return;
        }
        System.out.println("stanceReview: 사람이 summary가 각 입장을 보존했는지 확인");
        for (var item : reviewItems) {
            System.out.printf("- expectedCluster=%s, sourceMessageIds=%s, expectedMeaning=%s%n",
                    item.expectedClusterId(), item.requiredStance().messageIds(), item.requiredStance().meaning());
            for (var reference : item.actualTopicReferences()) {
                System.out.printf("  actualTopic[%d] category=%s, summary=%s, sourceMessageIds=%s%n",
                        reference.actualTopicIndex(), reference.category(), reference.summary(),
                        reference.sourceMessageIds());
            }
        }
    }

    private void printMustNotClaimReviewItems(List<String> mustNotClaims) {
        System.out.println("mustNotClaimReview: 사람이 summary에 다음 의미가 생성되지 않았는지 확인");
        for (String mustNotClaim : mustNotClaims) {
            System.out.println("- " + mustNotClaim);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Dataset(List<Scenario> scenarios) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Scenario(
            String scenarioId,
            List<ScenarioMessage> messages,
            List<ExpectedCluster> expectedClusters,
            List<Long> mustCoverMessageIds,
            List<String> mustNotClaim
    ) {
    }

    private record ScenarioMessage(long id, String content) {
    }

    private record ExpectedCluster(String clusterId, List<Long> messageIds, List<String> allowedCategories,
                                   List<RequiredStance> requiredStances) {
        ExpectedCluster {
            requiredStances = requiredStances == null ? List.of() : List.copyOf(requiredStances);
        }
    }

    private record RequiredStance(List<Long> messageIds, String meaning) {
    }
}
