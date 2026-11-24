package com.react.ai.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.ai.config.AudienceAnalysisCostProperties;
import com.react.ai.config.GeminiAudienceAnalysisProperties;
import com.react.ai.dto.AiAnalysisResult;
import com.react.ai.dto.AiTopicResult;
import com.react.ai.dto.AudienceAnalysisLlmResponse;
import com.react.ai.dto.AudienceMessage;
import com.react.ai.dto.PreprocessedAudienceMessages;
import com.react.ai.exception.GeminiAudienceAnalysisException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기존 8개 fixture의 운영 전처리 경로 품질 확인용 opt-in 테스트다. 기본 Gradle build에서는 외부 호출하지 않는다.
 * 실행: RUN_GEMINI_EIGHT_FIXTURE_QUALITY_TEST=true ./gradlew test --rerun-tasks --tests
 *       com.react.ai.service.GeminiAudienceAnalysisEightFixtureQualityTest
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
@EnabledIf("experimentEnabled")
class GeminiAudienceAnalysisEightFixtureQualityTest {
    private static final int REPETITIONS = 3;
    private static final List<String> SCENARIO_IDS = List.of(
            "similar-redis-questions",
            "mixed-question-opinion-request",
            "opposing-architecture-opinions",
            "concrete-request",
            "confusion-about-isolation",
            "small-unrelated-chat",
            "meaningless-chat-only",
            "boundary-multiple-topics-opposition-and-injection"
    );
    private static final Path REPORT_DIRECTORY = Path.of("build/reports/ai-quality/eight-fixture-quality");
    private static final Path RERUN_REPORT_DIRECTORY = Path.of("build/reports/ai-quality/eight-fixture-quality-rerun");
    private static final Path ORIGINAL_CSV = REPORT_DIRECTORY.resolve("gemini-audience-eight-fixture-quality.csv");
    private static final long FAILURE_RERUN_DELAY_MILLIS = 3_000L;

    @Autowired AudienceAnalysisLlmClient llmClient;
    @Autowired AiAnalysisJsonParser jsonParser;
    @Autowired GeminiAudienceAnalysisProperties geminiProperties;
    @Autowired AudienceAnalysisCostProperties costProperties;
    @Autowired ObjectMapper objectMapper;

    private final AudienceMessagePreprocessor preprocessor = new AudienceMessagePreprocessor();
    private final AudienceAnalysisClusteringEvaluator clusteringEvaluator = new AudienceAnalysisClusteringEvaluator();
    private final AiLlmCostCalculator costCalculator = new AiLlmCostCalculator();

    static boolean experimentEnabled() {
        return "true".equals(System.getenv("RUN_GEMINI_EIGHT_FIXTURE_QUALITY_TEST"))
                || "true".equals(System.getenv("RUN_GEMINI_EIGHT_FIXTURE_FAILURE_RERUN_TEST"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_GEMINI_EIGHT_FIXTURE_QUALITY_TEST", matches = "true")
    void 기존_8개_fixture를_운영_전처리와_동일하게_반복_평가하고_보고서를_생성한다() throws Exception {
        assertThat(geminiProperties.apiKey()).as("GEMINI_API_KEY 설정 여부").isNotBlank();
        assertThat(geminiProperties.model()).as("GEMINI_MODEL 설정 여부").isNotBlank();
        List<Scenario> scenarios = loadScenarios();
        List<RunResult> results = new ArrayList<>();

        for (Scenario scenario : scenarios) {
            PreprocessedAudienceMessages preprocessed = preprocess(scenario);
            for (int repetition = 1; repetition <= REPETITIONS; repetition++) {
                results.add(analyze(scenario, repetition, preprocessed));
            }
        }

        Files.createDirectories(REPORT_DIRECTORY);
        Path markdown = REPORT_DIRECTORY.resolve("gemini-audience-eight-fixture-quality.md");
        Path csv = REPORT_DIRECTORY.resolve("gemini-audience-eight-fixture-quality.csv");
        Files.writeString(markdown, markdownReport(results));
        Files.writeString(csv, csvReport(results));

        long plannedCalls = scenarios.stream()
                .map(this::preprocess)
                .filter(preprocessed -> AudienceAnalysisFilterComparisonSupport.shouldCallGemini(preprocessed.inputMessages()))
                .count() * REPETITIONS;
        assertThat(results).hasSize(SCENARIO_IDS.size() * REPETITIONS);
        System.out.printf("model=%s%n", geminiProperties.model());
        System.out.println("GEMINI_API_KEY present=true (value omitted)");
        System.out.printf("planned Gemini requests=%d, actual attempted=%d%n", plannedCalls,
                results.stream().filter(RunResult::callAttempted).count());
        System.out.println("Markdown report=" + markdown);
        System.out.println("CSV report=" + csv);
    }

    /**
     * 기존 보고서의 실패 표본만 한 번씩 재실행한다. 원본 보고서는 절대 덮어쓰지 않고 별도
     * 디렉터리에 재실행 및 결합 보고서를 남긴다.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_GEMINI_EIGHT_FIXTURE_FAILURE_RERUN_TEST", matches = "true")
    void 기존_실패_표본만_재실행하고_원본과_결합한_보고서를_생성한다() throws Exception {
        assertThat(geminiProperties.apiKey()).as("GEMINI_API_KEY 설정 여부").isNotBlank();
        assertThat(geminiProperties.model()).as("GEMINI_MODEL 설정 여부").isNotBlank();
        assertThat(ORIGINAL_CSV).as("기존 8개 fixture CSV 보고서").exists();

        List<HistoricalResult> originalResults = loadHistoricalResults();
        Map<String, Scenario> scenarioById = loadScenarios().stream()
                .collect(Collectors.toMap(Scenario::scenarioId, scenario -> scenario));
        List<HistoricalResult> failedTargets = originalResults.stream()
                .filter(HistoricalResult::retryableFailure)
                .toList();
        assertThat(failedTargets).as("재실행할 기존 실패 표본").isNotEmpty();

        List<RunResult> rerunResults = new ArrayList<>();
        for (int index = 0; index < failedTargets.size(); index++) {
            HistoricalResult target = failedTargets.get(index);
            Scenario scenario = scenarioById.get(target.scenarioId());
            assertThat(scenario).as("재실행 fixture: %s", target.scenarioId()).isNotNull();
            PreprocessedAudienceMessages preprocessed = preprocess(scenario);
            rerunResults.add(analyze(scenario, target.repetition(), preprocessed));
            if (index + 1 < failedTargets.size()) {
                waitBeforeNextFailureRerun();
            }
        }

        Files.createDirectories(RERUN_REPORT_DIRECTORY);
        List<CombinedResult> combined = new ArrayList<>();
        originalResults.forEach(result -> combined.add(result.toCombined()));
        rerunResults.forEach(result -> combined.add(CombinedResult.fromRerun(result)));
        Path rerunMarkdown = RERUN_REPORT_DIRECTORY.resolve("gemini-audience-eight-fixture-failure-rerun.md");
        Path combinedMarkdown = RERUN_REPORT_DIRECTORY.resolve("gemini-audience-eight-fixture-quality-combined.md");
        Path combinedCsv = RERUN_REPORT_DIRECTORY.resolve("gemini-audience-eight-fixture-quality-combined.csv");
        Files.writeString(rerunMarkdown, rerunMarkdownReport(failedTargets, rerunResults));
        Files.writeString(combinedMarkdown, combinedMarkdownReport(combined, scenarioById));
        Files.writeString(combinedCsv, combinedCsvReport(combined));

        System.out.printf("planned additional Gemini requests=%d, actual attempted=%d, delayBetweenRequestsMs=%d%n",
                failedTargets.size(), rerunResults.stream().filter(RunResult::callAttempted).count(),
                FAILURE_RERUN_DELAY_MILLIS);
        System.out.println("Original reports preserved under=" + REPORT_DIRECTORY);
        System.out.println("Failure rerun report=" + rerunMarkdown);
        System.out.println("Combined report=" + combinedMarkdown);
        System.out.println("Combined CSV=" + combinedCsv);
    }

    private List<HistoricalResult> loadHistoricalResults() throws Exception {
        List<String> lines = Files.readAllLines(ORIGINAL_CSV);
        assertThat(lines).as("기존 CSV 행").hasSizeGreaterThan(1);
        List<String> headers = csvColumns(lines.getFirst());
        List<HistoricalResult> results = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            List<String> columns = csvColumns(line);
            assertThat(columns).hasSize(headers.size());
            Map<String, String> row = new HashMap<>();
            for (int index = 0; index < headers.size(); index++) {
                row.put(headers.get(index), columns.get(index));
            }
            results.add(HistoricalResult.from(row));
        }
        return List.copyOf(results);
    }

    private List<String> csvColumns(String line) {
        List<String> columns = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                columns.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        assertThat(quoted).as("CSV 따옴표 닫힘").isFalse();
        columns.add(current.toString());
        return columns;
    }

    private void waitBeforeNextFailureRerun() {
        try {
            Thread.sleep(FAILURE_RERUN_DELAY_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("실패 표본 재실행 대기 중 인터럽트되었습니다.", exception);
        }
    }

    private String rerunMarkdownReport(List<HistoricalResult> failedTargets, List<RunResult> rerunResults) {
        StringBuilder report = new StringBuilder("# Gemini 기존 실패 표본 재실행\n\n")
                .append("- 원본 보고서: `").append(ORIGINAL_CSV).append("` (읽기 전용, 덮어쓰지 않음)\n")
                .append("- 재실행 대상: 기존 실패 ").append(failedTargets.size()).append("건만 각각 1회 호출\n")
                .append("- 재실행 간 대기: ").append(FAILURE_RERUN_DELAY_MILLIS).append("ms\n")
                .append("- 실패한 재실행은 같은 실행 내에서 다시 호출하지 않는다.\n")
                .append("- 질문 정답 라벨이 없는 fixture의 질문 재현율은 N/A다.\n\n")
                .append("| 시나리오 | 기존 회차/상태 | 재실행 상태 | P/R/F1 | 질문 재현율 | 성공 지연 ms | 입력/출력 토큰 | 추정 비용 microUSD |\n")
                .append("|---|---|---|---|---|---:|---|---:|\n");
        for (int index = 0; index < failedTargets.size(); index++) {
            HistoricalResult original = failedTargets.get(index);
            RunResult rerun = rerunResults.get(index);
            report.append("| ").append(original.scenarioId()).append(" | ")
                    .append(original.repetition()).append(" / ").append(original.status()).append(" | ")
                    .append(rerun.status()).append(" | ")
                    .append(score(rerun.precision())).append("/").append(score(rerun.recall())).append("/")
                    .append(score(rerun.f1())).append(" | ").append(score(rerun.questionRecall())).append(" | ")
                    .append(value(rerun.elapsedMillis())).append(" | ").append(value(rerun.inputTokens())).append("/")
                    .append(value(rerun.outputTokens())).append(" | ").append(value(rerun.estimatedCostMicrousd())).append(" |\n");
            for (TopicOutput topic : rerun.topics()) {
                report.append("\n- ").append(original.scenarioId()).append(" / rerun of ").append(original.repetition())
                        .append(" topic=`").append(topic.topic()).append("`, category=`").append(topic.category())
                        .append("`, source IDs=").append(topic.sourceMessageIds()).append("\n  summary: ")
                        .append(topic.summary()).append("\n");
            }
        }
        return report.toString();
    }

    private String combinedMarkdownReport(List<CombinedResult> rows, Map<String, Scenario> scenarios) {
        StringBuilder report = new StringBuilder("# Gemini 기존 8개 Fixture 품질 검증 - 원본·실패 재실행 결합\n\n")
                .append("- 원본 24개 표본은 보존했고, 원본 실패 표본만 별도 1회씩 재실행했다.\n")
                .append("- 지연은 성공/실패 요청을 분리한다. 성공 지연은 단일 Gemini 요청 기준이며 운영 재시도 지연은 포함하지 않는다.\n")
                .append("- 토큰·비용은 usageMetadata가 있는 성공 요청의 합계다. 비용은 환경변수 단가 기반 추정 microUSD다.\n")
                .append("- questionMessageIds가 없는 fixture의 질문 재현율은 N/A이며, 1.000으로 해석하지 않는다.\n\n")
                .append("| 시나리오 | 원본 성공/3 | 재실행 성공/실패 | 성공 결과 P/R/F1 | 질문 재현율 | 성공 평균/p95 ms | 실패 평균 ms | 입력/출력 토큰 | 추정 비용 microUSD |\n")
                .append("|---|---:|---:|---|---:|---:|---:|---:|---:|\n");
        for (String scenarioId : SCENARIO_IDS) {
            List<CombinedResult> subset = rows.stream().filter(row -> row.scenarioId().equals(scenarioId)).toList();
            List<CombinedResult> original = subset.stream().filter(row -> row.source().equals("ORIGINAL")).toList();
            List<CombinedResult> rerun = subset.stream().filter(row -> row.source().equals("RERUN")).toList();
            List<CombinedResult> successful = subset.stream().filter(CombinedResult::successful).toList();
            List<CombinedResult> failed = subset.stream().filter(CombinedResult::retryableFailure).toList();
            List<Long> successLatencies = successful.stream().map(CombinedResult::elapsedMillis)
                    .filter(java.util.Objects::nonNull).toList();
            List<Long> failureLatencies = failed.stream().map(CombinedResult::elapsedMillis)
                    .filter(java.util.Objects::nonNull).toList();
            boolean hasQuestionLabels = scenarios.get(scenarioId).questionMessageIds() != null
                    && !scenarios.get(scenarioId).questionMessageIds().isEmpty();
            report.append("| ").append(scenarioId).append(" | ")
                    .append(original.stream().filter(CombinedResult::successful).count()).append("/3 | ")
                    .append(rerun.stream().filter(CombinedResult::successful).count()).append("/")
                    .append(rerun.stream().filter(CombinedResult::retryableFailure).count()).append(" | ")
                    .append(averageScoreCombined(successful, CombinedResult::precision)).append("/")
                    .append(averageScoreCombined(successful, CombinedResult::recall)).append("/")
                    .append(averageScoreCombined(successful, CombinedResult::f1)).append(" | ")
                    .append(hasQuestionLabels ? averageScoreCombined(successful, CombinedResult::questionRecall) : "N/A")
                    .append(" | ").append(average(successLatencies)).append("/")
                    .append(successLatencies.isEmpty() ? "N/A" : AudienceAnalysisFilterComparisonSupport.p95Millis(successLatencies))
                    .append(" | ").append(average(failureLatencies)).append(" | ")
                    .append(sumKnownFromSuccessful(successful, CombinedResult::inputTokens)).append("/")
                    .append(sumKnownFromSuccessful(successful, CombinedResult::outputTokens)).append(" | ")
                    .append(sumKnownFromSuccessful(successful, CombinedResult::estimatedCostMicrousd)).append(" |\n");
        }
        return report.toString();
    }

    private String combinedCsvReport(List<CombinedResult> rows) {
        StringBuilder csv = new StringBuilder("source,scenarioId,repetition,status,model,elapsedMillis,pairwisePrecision,pairwiseRecall,pairwiseF1,questionRecall,inputTokens,outputTokens,estimatedCostMicrousd\n");
        for (CombinedResult row : rows) {
            csv.append(List.of(row.source(), row.scenarioId(), Integer.toString(row.repetition()), row.status(),
                            value(row.model()), value(row.elapsedMillis()), value(row.precision()), value(row.recall()),
                            value(row.f1()), value(row.questionRecall()), value(row.inputTokens()),
                            value(row.outputTokens()), value(row.estimatedCostMicrousd()))
                    .stream().map(this::csvCell).collect(Collectors.joining(","))).append('\n');
        }
        return csv.toString();
    }

    private String averageScoreCombined(List<CombinedResult> results,
                                        java.util.function.Function<CombinedResult, Double> extractor) {
        List<Double> values = results.stream().map(extractor).filter(java.util.Objects::nonNull).toList();
        return values.isEmpty() ? "N/A" : String.format("%.3f", values.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
    }

    private String sumKnownFromSuccessful(List<CombinedResult> results,
                                           java.util.function.Function<CombinedResult, Long> extractor) {
        List<Long> values = results.stream().map(extractor).filter(java.util.Objects::nonNull).toList();
        return values.isEmpty() ? "N/A" : Long.toString(values.stream().mapToLong(Long::longValue).sum());
    }

    private RunResult analyze(Scenario scenario, int repetition, PreprocessedAudienceMessages preprocessed) {
        int rawCount = scenario.messages().size();
        int retainedOriginalCount = preprocessed.originalMessageIdsByRepresentative().values().stream()
                .mapToInt(List::size).sum();
        int noiseRemoved = rawCount - retainedOriginalCount;
        int duplicatesMerged = retainedOriginalCount - preprocessed.inputMessages().size();
        var invocation = AudienceAnalysisFilterComparisonSupport.invoke(llmClient, preprocessed.inputMessages());
        if (!invocation.attempted()) {
            return skippedResult(scenario, repetition, noiseRemoved, duplicatesMerged);
        }
        if (invocation.failure() != null) {
            GeminiAudienceAnalysisException gemini = invocation.failure() instanceof GeminiAudienceAnalysisException exception
                    ? exception : null;
            return failedResult(scenario, repetition, invocation.elapsedMillis(), noiseRemoved, duplicatesMerged,
                    gemini == null ? geminiProperties.model() : geminiProperties.model(),
                    gemini == null ? null : gemini.usage(),
                    gemini == null ? "INVALID_RESPONSE_OR_RESULT" : gemini.errorCode().name());
        }

        AudienceAnalysisLlmResponse response = invocation.response();
        try {
            AiAnalysisResult parsed = jsonParser.parse(response.content());
            AiAnalysisResult expanded = AudienceAnalysisFilterComparisonSupport.expandSourceMessageIds(
                    parsed, preprocessed.originalMessageIdsByRepresentative(),
                    scenario.messages().stream().map(ScenarioMessage::id).toList());
            return evaluatedResult(scenario, repetition, invocation.elapsedMillis(), noiseRemoved, duplicatesMerged,
                    response, expanded);
        } catch (AudienceAnalysisFilterComparisonSupport.SourceMessageValidationException exception) {
            return failedResult(scenario, repetition, invocation.elapsedMillis(), noiseRemoved, duplicatesMerged,
                    response.modelName(), response.usage(),
                    AudienceAnalysisFilterComparisonSupport.sourceIdFailureStatus(exception));
        } catch (RuntimeException exception) {
            return failedResult(scenario, repetition, invocation.elapsedMillis(), noiseRemoved, duplicatesMerged,
                    response.modelName(), response.usage(), "INVALID_RESPONSE_OR_RESULT");
        }
    }

    private RunResult evaluatedResult(Scenario scenario, int repetition, long elapsedMillis, int noiseRemoved,
                                      int duplicatesMerged, AudienceAnalysisLlmResponse response,
                                      AiAnalysisResult expanded) {
        List<AiTopicResult> topics = expanded.topics();
        List<List<Long>> expectedGroups = scenario.expectedClusters().stream().map(ExpectedCluster::messageIds).toList();
        List<List<Long>> actualGroups = topics.stream().map(AiTopicResult::sourceMessageIds).toList();
        Set<Long> allOriginalIds = scenario.messages().stream().map(ScenarioMessage::id).collect(Collectors.toSet());
        try {
            var clustering = clusteringEvaluator.evaluate(allOriginalIds, expectedGroups, actualGroups);
            var quality = clusteringEvaluator.evaluateQuality(
                    scenario.mustCoverMessageIds(), expectedClusters(scenario), actualTopics(topics), scenario.mustNotClaim());
            return new RunResult(scenario.scenarioId(), repetition, "OK", response.modelName(), elapsedMillis,
                    clustering.precision(), clustering.recall(), clustering.f1(),
                    AudienceAnalysisFilterComparisonSupport.questionRecall(scenario.questionMessageIds(), actualTopics(topics)),
                    quality.missingMustCoverMessageIds(), quality.categoryViolations().size(), noiseRemoved,
                    duplicatesMerged, tokens(response.usage(), true), tokens(response.usage(), false),
                    estimatedCost(response.usage()), topics.stream().map(this::topicOutput).toList(),
                    quality.stanceReviewItems().stream().map(this::stanceOutput).toList(),
                    quality.mustNotClaimsForHumanReview(), true);
        } catch (IllegalArgumentException exception) {
            return failedResult(scenario, repetition, elapsedMillis, noiseRemoved, duplicatesMerged,
                    response.modelName(), response.usage(), "SOURCE_ID_VALIDATION_FAILED");
        }
    }

    private RunResult skippedResult(Scenario scenario, int repetition, int noiseRemoved, int duplicatesMerged) {
        return new RunResult(scenario.scenarioId(), repetition, "SKIPPED_EMPTY_INPUT", geminiProperties.model(), null,
                1.0, 1.0, 1.0,
                AudienceAnalysisFilterComparisonSupport.questionRecall(scenario.questionMessageIds(), List.of()),
                List.of(), 0, noiseRemoved, duplicatesMerged, null, null, null,
                List.of(), List.of(), scenario.mustNotClaim(), false);
    }

    private RunResult failedResult(Scenario scenario, int repetition, long elapsedMillis, int noiseRemoved,
                                   int duplicatesMerged, String model, AudienceAnalysisLlmResponse.Usage usage,
                                   String status) {
        return new RunResult(scenario.scenarioId(), repetition, status, model, elapsedMillis,
                null, null, null, null, scenario.mustCoverMessageIds(), null, noiseRemoved, duplicatesMerged,
                tokens(usage, true), tokens(usage, false), estimatedCost(usage),
                List.of(), List.of(), scenario.mustNotClaim(), true);
    }

    private PreprocessedAudienceMessages preprocess(Scenario scenario) {
        List<AudienceMessage> messages = scenario.messages().stream()
                .map(message -> new AudienceMessage(message.id(), 1L, message.content(),
                        LocalDateTime.of(2026, 1, 1, 0, 0)))
                .toList();
        return preprocessor.preprocess(messages);
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

    private String markdownReport(List<RunResult> results) {
        StringBuilder report = new StringBuilder("# Gemini 청중 채팅 기존 8개 Fixture 품질 검증\n\n")
                .append("- 모델: `").append(geminiProperties.model()).append("`\n")
                .append("- 범위: 기존 8개 fixture만 포함한다. `noise-mixed-discussion`, `noise-only-empty` 필터링 전후 비교 결과는 별도 보고서에서 관리한다.\n")
                .append("- 반복: 시나리오당 ").append(REPETITIONS).append("회, 현재 운영 서비스와 동일한 전처리·대표 ID 원문 확장·ID 중복 검증을 적용한다.\n")
                .append("- 응답 지연은 각 단일 Gemini API 요청 기준이며 운영 재시도 지연은 포함하지 않는다.\n")
                .append("- 비용은 usageMetadata와 `GEMINI_INPUT_USD_PER_MILLION_TOKENS`, `GEMINI_OUTPUT_USD_PER_MILLION_TOKENS`로 계산한 추정 microUSD다. 두 단가를 공식 모델 가격과 검증하지 못했거나 usageMetadata가 없으면 N/A로 기록한다.\n")
                .append("- questionMessageIds가 없는 fixture의 질문 재현율은 N/A로 기록하며, 정답 라벨 부재를 100%로 해석하지 않는다.\n")
                .append("- p95는 nearest-rank 방식이며 조건별 3회 표본의 최댓값이다. 초기 관측값으로 운영 SLA를 보장하지 않는다.\n")
                .append("- 모든 fixture 발언은 합성 데이터다. API 키와 실제 청중 개인정보는 기록하지 않는다.\n\n")
                .append("## 시나리오별 집계\n\n")
                .append("| 시나리오 | 호출 시도 | 평균 P/R/F1 | 질문 재현율 | must-cover 누락 run | category 위반 | 평균 응답 ms | p95 ms | 입력/출력 토큰 합 | 추정 비용 microUSD 합 |\n")
                .append("|---|---:|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (String scenarioId : SCENARIO_IDS) {
            List<RunResult> subset = results.stream().filter(result -> result.scenarioId().equals(scenarioId)).toList();
            List<Long> latencies = subset.stream().map(RunResult::elapsedMillis).filter(java.util.Objects::nonNull).toList();
            report.append("| ").append(scenarioId).append(" | ")
                    .append(subset.stream().filter(RunResult::callAttempted).count()).append(" | ")
                    .append(averageScore(subset, RunResult::precision)).append("/")
                    .append(averageScore(subset, RunResult::recall)).append("/")
                    .append(averageScore(subset, RunResult::f1)).append(" | ")
                    .append(averageScore(subset, RunResult::questionRecall)).append(" | ")
                    .append(subset.stream().filter(result -> !result.missingMustCover().isEmpty()).count()).append(" | ")
                    .append(sumIntegers(subset.stream().map(RunResult::categoryViolationCount).toList())).append(" | ")
                    .append(average(latencies)).append(" | ")
                    .append(latencies.isEmpty() ? "N/A" : AudienceAnalysisFilterComparisonSupport.p95Millis(latencies)).append(" | ")
                    .append(sumKnown(subset.stream().map(RunResult::inputTokens).toList())).append("/")
                    .append(sumKnown(subset.stream().map(RunResult::outputTokens).toList())).append(" | ")
                    .append(sumKnown(subset.stream().map(RunResult::estimatedCostMicrousd).toList())).append(" |\n");
        }
        report.append("\n## 실행별 결과\n\n")
                .append("| 시나리오 | 회차 | 상태 | P/R/F1 | 질문 재현율 | must-cover 누락 | category 위반 | 노이즈 제외 | 중복 병합 | 입력/출력 토큰 | 비용 microUSD |\n")
                .append("|---|---:|---|---|---:|---|---:|---:|---:|---|---:|\n");
        for (RunResult result : results) {
            report.append("| ").append(result.scenarioId()).append(" | ").append(result.repetition()).append(" | ")
                    .append(result.status()).append(" | ").append(score(result.precision())).append("/")
                    .append(score(result.recall())).append("/").append(score(result.f1())).append(" | ")
                    .append(score(result.questionRecall())).append(" | ").append(result.missingMustCover()).append(" | ")
                    .append(result.categoryViolationCount()).append(" | ").append(result.noiseRemoved()).append(" | ")
                    .append(result.duplicatesMerged()).append(" | ").append(value(result.inputTokens())).append("/")
                    .append(value(result.outputTokens())).append(" | ").append(value(result.estimatedCostMicrousd()))
                    .append(" |\n");
            for (TopicOutput topic : result.topics()) {
                report.append("\n- ").append(result.scenarioId()).append(" run ").append(result.repetition())
                        .append(" topic=`").append(topic.topic()).append("`, category=`").append(topic.category())
                        .append("`, source IDs=").append(topic.sourceMessageIds()).append("\n  summary: ")
                        .append(topic.summary()).append("\n");
            }
        }
        report.append("\n## 사람 검토 항목\n\n각 run에서 required stance가 summary에 보존됐는지, must-not-claim이 생성되지 않았는지, source IDs에 연결된 개별 질문·요청·이해 부족 표현이 summary에 실제 반영됐는지 확인한다.\n");
        for (RunResult result : results) {
            for (String item : result.stanceReview()) {
                report.append("\n- ").append(result.scenarioId()).append(" / run ").append(result.repetition())
                        .append(" / stance: ").append(item);
            }
            for (String item : result.mustNotClaimReview()) {
                report.append("\n- ").append(result.scenarioId()).append(" / run ").append(result.repetition())
                        .append(" / must-not-claim: ").append(item);
            }
        }
        return report.append('\n').toString();
    }

    private String csvReport(List<RunResult> results) throws Exception {
        StringBuilder csv = new StringBuilder("scenarioId,repetition,status,model,elapsedMillis,pairwisePrecision,pairwiseRecall,pairwiseF1,questionRecall,missingMustCover,categoryViolationCount,noiseRemoved,duplicatesMerged,inputTokens,outputTokens,estimatedCostMicrousd,topics,stanceReview,mustNotClaimReview\n");
        for (RunResult result : results) {
            List<Object> cells = List.of(result.scenarioId(), result.repetition(), result.status(), value(result.model()),
                    value(result.elapsedMillis()), value(result.precision()), value(result.recall()), value(result.f1()),
                    value(result.questionRecall()), objectMapper.writeValueAsString(result.missingMustCover()),
                    value(result.categoryViolationCount()), result.noiseRemoved(), result.duplicatesMerged(),
                    value(result.inputTokens()), value(result.outputTokens()), value(result.estimatedCostMicrousd()),
                    objectMapper.writeValueAsString(result.topics()), objectMapper.writeValueAsString(result.stanceReview()),
                    objectMapper.writeValueAsString(result.mustNotClaimReview()));
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

    private String averageScore(List<RunResult> results, java.util.function.Function<RunResult, Double> extractor) {
        List<Double> values = results.stream().map(extractor).filter(java.util.Objects::nonNull).toList();
        return values.isEmpty() ? "N/A" : String.format("%.3f", values.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private String average(List<Long> values) {
        Double average = AudienceAnalysisFilterComparisonSupport.averageMillis(values);
        return average == null ? "N/A" : String.format("%.1f", average);
    }

    private String sumKnown(List<Long> values) {
        Long total = AudienceAnalysisFilterComparisonSupport.sumKnown(values);
        return total == null ? "N/A" : total.toString();
    }

    private String sumIntegers(List<Integer> values) {
        return values.stream().anyMatch(java.util.Objects::isNull) ? "N/A"
                : Integer.toString(values.stream().mapToInt(Integer::intValue).sum());
    }

    private String score(Double value) {
        return value == null ? "N/A" : String.format("%.3f", value);
    }

    private String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private String csvCell(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private record RunResult(String scenarioId, int repetition, String status, String model, Long elapsedMillis,
                             Double precision, Double recall, Double f1, Double questionRecall,
                             List<Long> missingMustCover, Integer categoryViolationCount, int noiseRemoved,
                             int duplicatesMerged, Long inputTokens, Long outputTokens, Long estimatedCostMicrousd,
                             List<TopicOutput> topics, List<String> stanceReview, List<String> mustNotClaimReview,
                             boolean callAttempted) {
    }

    private record HistoricalResult(String scenarioId, int repetition, String status, String model, Long elapsedMillis,
                                    Double precision, Double recall, Double f1, Long inputTokens,
                                    Long outputTokens, Long estimatedCostMicrousd) {
        static HistoricalResult from(Map<String, String> row) {
            return new HistoricalResult(
                    row.get("scenarioId"), Integer.parseInt(row.get("repetition")), row.get("status"),
                    emptyToNull(row.get("model")), longValue(row.get("elapsedMillis")),
                    doubleValue(row.get("pairwisePrecision")), doubleValue(row.get("pairwiseRecall")),
                    doubleValue(row.get("pairwiseF1")), longValue(row.get("inputTokens")),
                    longValue(row.get("outputTokens")), longValue(row.get("estimatedCostMicrousd")));
        }

        boolean retryableFailure() {
            return !successful() && !"SKIPPED_EMPTY_INPUT".equals(status);
        }

        boolean successful() {
            return "OK".equals(status);
        }

        CombinedResult toCombined() {
            // 기존 8개 fixture에는 questionMessageIds가 없었다. 기존 CSV의 1.000은 결측 라벨의 기본값이므로 사용하지 않는다.
            return new CombinedResult("ORIGINAL", scenarioId, repetition, status, model, elapsedMillis,
                    precision, recall, f1, null, inputTokens, outputTokens, estimatedCostMicrousd);
        }

        private static String emptyToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }

        private static Long longValue(String value) {
            String normalized = emptyToNull(value);
            return normalized == null ? null : Long.parseLong(normalized);
        }

        private static Double doubleValue(String value) {
            String normalized = emptyToNull(value);
            return normalized == null ? null : Double.parseDouble(normalized);
        }
    }

    private record CombinedResult(String source, String scenarioId, int repetition, String status, String model,
                                  Long elapsedMillis, Double precision, Double recall, Double f1,
                                  Double questionRecall, Long inputTokens, Long outputTokens,
                                  Long estimatedCostMicrousd) {
        static CombinedResult fromRerun(RunResult result) {
            return new CombinedResult("RERUN", result.scenarioId(), result.repetition(), result.status(),
                    result.model(), result.elapsedMillis(), result.precision(), result.recall(), result.f1(),
                    result.questionRecall(), result.inputTokens(), result.outputTokens(),
                    result.estimatedCostMicrousd());
        }

        boolean successful() {
            return "OK".equals(status);
        }

        boolean retryableFailure() {
            return !successful() && !"SKIPPED_EMPTY_INPUT".equals(status);
        }

    }

    private record TopicOutput(String topic, String category, List<Long> sourceMessageIds, String summary) {
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
