package com.react.ai.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.ai.config.AudienceAnalysisCostProperties;
import com.react.ai.config.GeminiAudienceAnalysisProperties;
import com.react.ai.dto.AiAnalysisResult;
import com.react.ai.dto.AiTopicResult;
import com.react.ai.dto.AudienceAnalysisInputMessage;
import com.react.ai.dto.AudienceAnalysisLlmResponse;
import com.react.ai.dto.AudienceMessage;
import com.react.ai.dto.PreprocessedAudienceMessages;
import com.react.ai.exception.GeminiAudienceAnalysisException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Gemini 전처리 전후 비교용 opt-in 평가 도구. 기본 Gradle 빌드에서는 외부 호출하지 않는다.
 * 실행: RUN_GEMINI_FILTER_COMPARISON=true ./gradlew test --rerun-tasks --tests
 *       com.react.ai.service.GeminiAudienceAnalysisFilterComparisonTest
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
@EnabledIfEnvironmentVariable(named = "RUN_GEMINI_FILTER_COMPARISON", matches = "true")
class GeminiAudienceAnalysisFilterComparisonTest {
    private static final int REPETITIONS = 3;
    private static final List<String> SCENARIO_IDS = List.of("noise-mixed-discussion", "noise-only-empty");
    private static final Path REPORT_DIRECTORY = Path.of("build/reports/ai-quality/filter-comparison");

    @Autowired AudienceAnalysisLlmClient llmClient;
    @Autowired AiAnalysisJsonParser jsonParser;
    @Autowired GeminiAudienceAnalysisProperties geminiProperties;
    @Autowired AudienceAnalysisCostProperties costProperties;
    @Autowired ObjectMapper objectMapper;

    private final AudienceMessagePreprocessor preprocessor = new AudienceMessagePreprocessor();
    private final AudienceAnalysisClusteringEvaluator clusteringEvaluator = new AudienceAnalysisClusteringEvaluator();
    private final AiLlmCostCalculator costCalculator = new AiLlmCostCalculator();

    @Test
    void 필터링_전후를_동일_모델_프롬프트_설정으로_반복_평가하고_보고서를_생성한다() throws Exception {
        assertThat(geminiProperties.apiKey()).as("GEMINI_API_KEY 설정 여부").isNotBlank();
        assertThat(geminiProperties.model()).as("GEMINI_MODEL 설정 여부").isNotBlank();
        List<Scenario> scenarios = loadScenarios();
        List<RunResult> results = new ArrayList<>();

        for (Scenario scenario : scenarios) {
            PreparedInputs prepared = prepare(scenario);
            for (int repetition = 1; repetition <= REPETITIONS; repetition++) {
                results.add(analyze(scenario, "BEFORE", repetition, prepared.rawInput(), identityMap(scenario)));
                results.add(analyze(scenario, "AFTER", repetition, prepared.filtered().inputMessages(),
                        prepared.filtered().originalMessageIdsByRepresentative()));
            }
        }

        Files.createDirectories(REPORT_DIRECTORY);
        Path markdown = REPORT_DIRECTORY.resolve("gemini-audience-filter-comparison.md");
        Path csv = REPORT_DIRECTORY.resolve("gemini-audience-filter-comparison.csv");
        Files.writeString(markdown, markdownReport(results));
        Files.writeString(csv, csvReport(results));

        long plannedCalls = scenarios.stream().mapToLong(scenario -> {
            PreparedInputs prepared = prepare(scenario);
            return REPETITIONS * (1L + (AudienceAnalysisFilterComparisonSupport
                    .shouldCallGemini(prepared.filtered().inputMessages()) ? 1L : 0L));
        }).sum();
        assertThat(results).hasSize(scenarios.size() * REPETITIONS * 2);
        System.out.printf("model=%s%n", geminiProperties.model());
        System.out.println("GEMINI_API_KEY present=true (value omitted)");
        System.out.printf("planned Gemini requests=%d, actual attempted=%d%n", plannedCalls,
                results.stream().filter(result -> result.callAttempted()).count());
        System.out.println("Markdown report=" + markdown);
        System.out.println("CSV report=" + csv);
    }

    private RunResult analyze(Scenario scenario, String condition, int repetition,
                              List<AudienceAnalysisInputMessage> input,
                              Map<Long, List<Long>> originalIdsByRepresentative) {
        int rawCount = scenario.messages().size();
        int retainedOriginalCount = originalIdsByRepresentative.values().stream().mapToInt(List::size).sum();
        int removedNoiseCount = condition.equals("AFTER") ? rawCount - retainedOriginalCount : 0;
        int mergedOccurrences = condition.equals("AFTER") ? retainedOriginalCount - input.size() : 0;
        AudienceAnalysisFilterComparisonSupport.Invocation invocation =
                AudienceAnalysisFilterComparisonSupport.invoke(llmClient, input);
        if (!invocation.attempted()) {
            return skippedResult(scenario, condition, repetition, rawCount, removedNoiseCount, mergedOccurrences);
        }
        AudienceAnalysisLlmResponse response = invocation.response();
        if (invocation.failure() != null) {
            String errorCode = invocation.failure() instanceof GeminiAudienceAnalysisException exception
                    ? exception.errorCode().name() : "INVALID_RESPONSE_OR_RESULT";
            AudienceAnalysisLlmResponse.Usage failureUsage = invocation.failure() instanceof GeminiAudienceAnalysisException exception
                    ? exception.usage() : null;
            return failedResult(scenario, condition, repetition, rawCount, removedNoiseCount, mergedOccurrences,
                    invocation.elapsedMillis(), geminiProperties.model(), failureUsage, errorCode);
        }
        try {
            AiAnalysisResult parsed = jsonParser.parse(response.content());
            AiAnalysisResult expanded = AudienceAnalysisFilterComparisonSupport.expandSourceMessageIds(
                    parsed, originalIdsByRepresentative, scenario.messages().stream()
                            .map(ScenarioMessage::id).toList());
            return evaluate(scenario, condition, repetition, rawCount, removedNoiseCount, mergedOccurrences,
                    invocation.elapsedMillis(), response, expanded,
                    "OK", true);
        } catch (AudienceAnalysisFilterComparisonSupport.SourceMessageValidationException exception) {
            return failedResult(scenario, condition, repetition, rawCount, removedNoiseCount, mergedOccurrences,
                    invocation.elapsedMillis(), response.modelName(), response.usage(),
                    AudienceAnalysisFilterComparisonSupport.sourceIdFailureStatus(exception));
        } catch (RuntimeException exception) {
            return failedResult(scenario, condition, repetition, rawCount, removedNoiseCount, mergedOccurrences,
                    invocation.elapsedMillis(), response.modelName(), response.usage(), "INVALID_RESPONSE_OR_RESULT");
        }
    }

    private RunResult evaluate(Scenario scenario, String condition, int repetition, int rawCount,
                               int removedNoiseCount, int mergedOccurrences, long elapsedMillis,
                               AudienceAnalysisLlmResponse response, AiAnalysisResult result,
                               String status, boolean attempted) {
        List<AiTopicResult> expandedTopics = result.topics();
        List<List<Long>> expectedGroups = scenario.expectedClusters().stream().map(ExpectedCluster::messageIds).toList();
        List<List<Long>> actualGroups = expandedTopics.stream().map(AiTopicResult::sourceMessageIds).toList();
        Set<Long> allOriginalIds = scenario.messages().stream().map(ScenarioMessage::id).collect(Collectors.toSet());
        try {
            AudienceAnalysisClusteringEvaluator.ClusterEvaluation clustering = clusteringEvaluator.evaluate(
                    allOriginalIds, expectedGroups, actualGroups);
            AudienceAnalysisClusteringEvaluator.QualityEvaluation quality = clusteringEvaluator.evaluateQuality(
                    scenario.mustCoverMessageIds(), expectedClusters(scenario), actualTopics(expandedTopics),
                    scenario.mustNotClaim());
            return new RunResult(scenario.scenarioId(), condition, repetition, "OK", response.modelName(), elapsedMillis,
                    clustering.precision(), clustering.recall(), clustering.f1(),
                    AudienceAnalysisFilterComparisonSupport.questionRecall(scenario.questionMessageIds(), actualTopics(expandedTopics)),
                    quality.missingMustCoverMessageIds(), quality.categoryViolations().size(), removedNoiseCount,
                    mergedOccurrences, tokens(response.usage(), true), tokens(response.usage(), false), estimatedCost(response.usage()),
                    expandedTopics.stream().map(this::topicOutput).toList(),
                    quality.stanceReviewItems().stream().map(this::stanceOutput).toList(),
                    quality.mustNotClaimsForHumanReview(), attempted);
        } catch (IllegalArgumentException exception) {
            return failedResult(scenario, condition, repetition, rawCount, removedNoiseCount, mergedOccurrences,
                    elapsedMillis, response.modelName(), response.usage(), "SOURCE_ID_VALIDATION_FAILED");
        }
    }

    private RunResult skippedResult(Scenario scenario, String condition, int repetition, int rawCount,
                                    int removedNoiseCount, int mergedOccurrences) {
        return new RunResult(scenario.scenarioId(), condition, repetition, "SKIPPED_EMPTY_INPUT",
                geminiProperties.model(), null, 1.0, 1.0, 1.0,
                AudienceAnalysisFilterComparisonSupport.questionRecall(scenario.questionMessageIds(), List.of()),
                List.of(), 0, removedNoiseCount, mergedOccurrences, null, null, null,
                List.of(), List.of(), scenario.mustNotClaim(), false);
    }

    private RunResult failedResult(Scenario scenario, String condition, int repetition, int rawCount,
                                   int removedNoiseCount, int mergedOccurrences, long elapsedMillis, String model,
                                   AudienceAnalysisLlmResponse.Usage usage, String safeErrorCode) {
        return new RunResult(scenario.scenarioId(), condition, repetition, safeErrorCode, model, elapsedMillis,
                null, null, null, null, scenario.mustCoverMessageIds(), null, removedNoiseCount,
                mergedOccurrences, tokens(usage, true), tokens(usage, false), estimatedCost(usage),
                List.of(), List.of(), scenario.mustNotClaim(), true);
    }

    private RunResult failedResult(Scenario scenario, String condition, int repetition, int rawCount,
                                   int removedNoiseCount, int mergedOccurrences, long elapsedMillis, String model,
                                   Long inputTokens, Long outputTokens, String safeErrorCode) {
        return new RunResult(scenario.scenarioId(), condition, repetition, safeErrorCode, model, elapsedMillis,
                null, null, null, null, scenario.mustCoverMessageIds(), null, removedNoiseCount,
                mergedOccurrences, inputTokens, outputTokens, null,
                List.of(), List.of(), scenario.mustNotClaim(), true);
    }

    private PreparedInputs prepare(Scenario scenario) {
        List<AudienceAnalysisInputMessage> rawInput = scenario.messages().stream()
                .map(message -> new AudienceAnalysisInputMessage(message.id(), message.content(), 1)).toList();
        List<AudienceMessage> source = scenario.messages().stream()
                .map(message -> new AudienceMessage(message.id(), 1L, message.content(), LocalDateTime.of(2026, 1, 1, 0, 0)))
                .toList();
        return new PreparedInputs(rawInput, preprocessor.preprocess(source));
    }

    private Map<Long, List<Long>> identityMap(Scenario scenario) {
        Map<Long, List<Long>> identities = new LinkedHashMap<>();
        scenario.messages().forEach(message -> identities.put(message.id(), List.of(message.id())));
        return identities;
    }

    private List<AudienceAnalysisClusteringEvaluator.ExpectedCluster> expectedClusters(Scenario scenario) {
        return scenario.expectedClusters().stream().map(cluster -> new AudienceAnalysisClusteringEvaluator.ExpectedCluster(
                cluster.clusterId(), cluster.messageIds(), Set.copyOf(cluster.allowedCategories()),
                cluster.requiredStances().stream().map(stance -> new AudienceAnalysisClusteringEvaluator.RequiredStance(
                        stance.messageIds(), stance.meaning())).toList())).toList();
    }

    private List<AudienceAnalysisClusteringEvaluator.ActualTopic> actualTopics(List<AiTopicResult> topics) {
        return topics.stream().map(topic -> new AudienceAnalysisClusteringEvaluator.ActualTopic(
                topic.topic(), topic.category().name(), topic.summary(), topic.sourceMessageIds())).toList();
    }

    private Long tokens(AudienceAnalysisLlmResponse.Usage usage, boolean input) {
        return usage == null ? null : input ? usage.promptTokenCount() : usage.outputTokenCount();
    }

    private Long estimatedCost(AudienceAnalysisLlmResponse.Usage usage) {
        return costCalculator.estimate(usage, costProperties)
                .map(AiLlmCostCalculator.CostEstimate::estimatedCostMicrousd).orElse(null);
    }

    private List<Scenario> loadScenarios() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/ai/quality/gemini-audience-analysis-cases.json")) {
            assertThat(input).as("품질 fixture").isNotNull();
            Dataset dataset = objectMapper.readValue(input, Dataset.class);
            List<Scenario> selected = dataset.scenarios().stream()
                    .filter(scenario -> SCENARIO_IDS.contains(scenario.scenarioId())).toList();
            assertThat(selected).extracting(Scenario::scenarioId).containsExactlyElementsOf(SCENARIO_IDS);
            return selected;
        }
    }

    private String markdownReport(List<RunResult> results) {
        StringBuilder report = new StringBuilder("# Gemini 청중 채팅 노이즈 전처리 비교\n\n")
                .append("- 모델: `").append(geminiProperties.model()).append("`\n")
                .append("- 비교 범위: 추가된 `noise-mixed-discussion`, `noise-only-empty` 2개 시나리오만 포함한다. 기존 8개 시나리오 품질 검증은 별도 단계다.\n")
                .append("- 반복: 조건별 시나리오당 ").append(REPETITIONS).append("회\n")
                .append("- 조건: 동일 LLM client 설정·프롬프트에서 입력 전처리 여부만 다름\n")
                .append("- 응답 지연은 각 단일 Gemini API 요청 기준으로 측정하며, 실제 운영 서비스의 재시도 지연은 포함하지 않는다.\n")
                .append("- 비용은 모델 단가와 usageMetadata로 계산한 추정 microUSD이며 실제 청구액이 아니다. 실제 운영 서비스의 재시도 비용은 포함하지 않는다.\n")
                .append("- 질문 재현율: 기준 질문 ID 중 `QUESTION` 또는 `MIXED` 결과 그룹에 연결된 ID 비율\n")
                .append("- p95는 nearest-rank 방식이며 조건별 3회 표본의 최댓값이다. 소수 표본을 이용한 초기 관측값으로, 운영 환경의 SLA를 보장하지 않는다.\n")
                .append("- 모든 fixture 발언은 합성 데이터다. API 키와 실제 청중 개인정보는 기록하지 않는다.\n\n")
                .append("## 조건별 지연 및 호출\n\n")
                .append("| 시나리오 | 조건 | 호출 시도 | 평균 응답 ms | p95 응답 ms | 입력 토큰 합 | 출력 토큰 합 | 추정 비용 microUSD 합 |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (String scenarioId : SCENARIO_IDS) {
            for (String condition : List.of("BEFORE", "AFTER")) {
                List<RunResult> subset = results.stream().filter(row -> row.scenarioId().equals(scenarioId)
                        && row.condition().equals(condition)).toList();
                List<Long> latencies = subset.stream().filter(row -> row.elapsedMillis() != null)
                        .map(RunResult::elapsedMillis).toList();
                report.append("| ").append(scenarioId).append(" | ").append(condition).append(" | ")
                        .append(subset.stream().filter(RunResult::callAttempted).count()).append(" | ")
                        .append(average(latencies)).append(" | ")
                        .append(latencies.isEmpty() ? "N/A" : AudienceAnalysisFilterComparisonSupport.p95Millis(latencies))
                        .append(" | ").append(sumKnown(subset.stream().map(RunResult::inputTokens).toList()))
                        .append(" | ").append(sumKnown(subset.stream().map(RunResult::outputTokens).toList()))
                        .append(" | ").append(sumKnown(subset.stream().map(RunResult::estimatedCostMicrousd).toList()))
                        .append(" |\n");
            }
        }
        report.append("\n## 실행별 점수와 검토 자료\n\n")
                .append("| 시나리오 | 조건 | 회차 | 상태 | Pairwise P/R/F1 | 질문 재현율 | must-cover 누락 | category 위반 | 입력 감소 | 노이즈 제외 | 반복 병합 | 입력/출력 토큰 | 추정 비용 microUSD |\n")
                .append("|---|---|---:|---|---|---:|---|---:|---:|---:|---:|---|---:|\n");
        for (RunResult row : results) {
            report.append("| ").append(row.scenarioId()).append(" | ").append(row.condition()).append(" | ")
                    .append(row.repetition()).append(" | ").append(row.status()).append(" | ")
                    .append(score(row.precision())).append("/").append(score(row.recall())).append("/")
                    .append(score(row.f1())).append(" | ").append(score(row.questionRecall())).append(" | ")
                    .append(row.missingMustCover()).append(" | ").append(row.categoryViolationCount()).append(" | ")
                    .append(row.noiseRemoved() + row.duplicateOccurrencesMerged()).append(" | ")
                    .append(row.noiseRemoved()).append(" | ").append(row.duplicateOccurrencesMerged()).append(" | ")
                    .append(value(row.inputTokens())).append("/").append(value(row.outputTokens())).append(" | ")
                    .append(value(row.estimatedCostMicrousd())).append(" |\n");
            for (TopicOutput topic : row.topics()) {
                report.append("\n- ").append(row.scenarioId()).append(" ").append(row.condition())
                        .append(" run ").append(row.repetition()).append(" topic=`").append(topic.topic())
                        .append("`, category=`").append(topic.category()).append("`, source IDs=")
                        .append(topic.sourceMessageIds()).append("\n  summary: ").append(topic.summary()).append("\n");
            }
        }
        report.append("\n## 사람 검토 항목\n\n각 run의 요약이 필수 입장을 보존하고 mustNotClaim의 내용을 만들어내지 않았는지 직접 판정한다.\n");
        for (RunResult row : results) {
            for (String item : row.stanceReview()) {
                report.append("\n- ").append(row.scenarioId()).append(" / ").append(row.condition())
                        .append(" / run ").append(row.repetition()).append(" / stance: ").append(item);
            }
            for (String item : row.mustNotClaimReview()) {
                report.append("\n- ").append(row.scenarioId()).append(" / ").append(row.condition())
                        .append(" / run ").append(row.repetition()).append(" / must-not-claim: ").append(item);
            }
        }
        return report.append('\n').toString();
    }

    private String csvReport(List<RunResult> results) throws Exception {
        StringBuilder csv = new StringBuilder("scenarioId,condition,repetition,status,model,elapsedMillis,pairwisePrecision,pairwiseRecall,pairwiseF1,questionRecall,missingMustCover,categoryViolationCount,totalInputReduction,noiseRemoved,duplicateOccurrencesMerged,inputTokens,outputTokens,estimatedCostMicrousd,topics,stanceReview,mustNotClaimReview\n");
        for (RunResult row : results) {
            List<Object> cells = List.of(row.scenarioId(), row.condition(), row.repetition(), row.status(),
                    value(row.model()), value(row.elapsedMillis()), value(row.precision()), value(row.recall()),
                    value(row.f1()), value(row.questionRecall()), objectMapper.writeValueAsString(row.missingMustCover()),
                    value(row.categoryViolationCount()), row.noiseRemoved() + row.duplicateOccurrencesMerged(),
                    row.noiseRemoved(), row.duplicateOccurrencesMerged(),
                    value(row.inputTokens()), value(row.outputTokens()), value(row.estimatedCostMicrousd()),
                    objectMapper.writeValueAsString(row.topics()), objectMapper.writeValueAsString(row.stanceReview()),
                    objectMapper.writeValueAsString(row.mustNotClaimReview()));
            csv.append(cells.stream().map(cell -> csvCell(String.valueOf(cell))).collect(Collectors.joining(",")))
                    .append('\n');
        }
        return csv.toString();
    }

    private TopicOutput topicOutput(AiTopicResult topic) {
        return new TopicOutput(topic.topic(), topic.category().name(), topic.sourceMessageIds(), topic.summary());
    }

    private String stanceOutput(AudienceAnalysisClusteringEvaluator.StanceReviewItem item) {
        return item.expectedClusterId() + " IDs=" + item.requiredStance().messageIds() + ": "
                + item.requiredStance().meaning() + " -> " + item.actualTopicReferences().stream()
                .map(reference -> reference.summary()).toList();
    }

    private String average(List<Long> samples) {
        Double average = AudienceAnalysisFilterComparisonSupport.averageMillis(samples);
        return average == null ? "N/A" : String.format("%.1f", average);
    }

    private String sumKnown(List<Long> values) {
        Long total = AudienceAnalysisFilterComparisonSupport.sumKnown(values);
        return total == null ? "N/A" : total.toString();
    }

    private String score(Double score) {
        return score == null ? "N/A" : String.format("%.3f", score);
    }

    private String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private String csvCell(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private record PreparedInputs(List<AudienceAnalysisInputMessage> rawInput,
                                  PreprocessedAudienceMessages filtered) {
    }

    private record TopicOutput(String topic, String category, List<Long> sourceMessageIds, String summary) {
    }

    private record RunResult(String scenarioId, String condition, int repetition, String status, String model,
                             Long elapsedMillis, Double precision, Double recall, Double f1, Double questionRecall,
                             List<Long> missingMustCover, Integer categoryViolationCount, int noiseRemoved,
                             int duplicateOccurrencesMerged, Long inputTokens, Long outputTokens,
                             Long estimatedCostMicrousd, List<TopicOutput> topics, List<String> stanceReview,
                             List<String> mustNotClaimReview, boolean callAttempted) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Dataset(List<Scenario> scenarios) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Scenario(String scenarioId, List<ScenarioMessage> messages,
                            List<ExpectedCluster> expectedClusters, List<Long> mustCoverMessageIds,
                            List<Long> questionMessageIds, List<String> mustNotClaim) {
        Scenario {
            expectedClusters = expectedClusters == null ? List.of() : List.copyOf(expectedClusters);
            mustCoverMessageIds = mustCoverMessageIds == null ? List.of() : List.copyOf(mustCoverMessageIds);
            questionMessageIds = questionMessageIds == null ? List.of() : List.copyOf(questionMessageIds);
            mustNotClaim = mustNotClaim == null ? List.of() : List.copyOf(mustNotClaim);
        }
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
