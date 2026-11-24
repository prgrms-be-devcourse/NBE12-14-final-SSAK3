package com.react.ai.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Re-evaluates saved Gemini outputs only; it never creates an API request. */
@EnabledIfEnvironmentVariable(named = "RUN_GEMINI_EIGHT_FIXTURE_QUESTION_EVALUATION_TEST", matches = "true")
class GeminiAudienceAnalysisQuestionEvaluationReportTest {
    private static final Path ORIGINAL_CSV = Path.of("build/reports/ai-quality/eight-fixture-quality/gemini-audience-eight-fixture-quality.csv");
    private static final Path RERUN_MARKDOWN = Path.of("build/reports/ai-quality/eight-fixture-quality-rerun/gemini-audience-eight-fixture-failure-rerun.md");
    private static final Path REPORT = Path.of("build/reports/ai-quality/eight-fixture-quality-rerun/gemini-audience-eight-fixture-question-evaluation.md");
    private static final Pattern RERUN_STATUS = Pattern.compile("^\\| ([^|]+) \\| (\\d+) / [^|]+ \\| ([^|]+) \\| ([^/|]+)/([^/|]+)/([^|]+) \\|.*$");
    private static final Pattern RERUN_TOPIC = Pattern.compile("^- (.+) / rerun of (\\d+) topic=`[^`]+`, category=`([^`]+)`, source IDs=\\[(.*)]$");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AudienceAnalysisQuestionTypeEvaluator evaluator = new AudienceAnalysisQuestionTypeEvaluator();

    @Test
    void 저장된_24개_Gemini_응답으로_질문_포착률과_유형_분류를_재평가한다() throws Exception {
        assertThat(ORIGINAL_CSV).exists();
        assertThat(RERUN_MARKDOWN).exists();
        Map<String, Scenario> scenarios = loadScenarios();
        List<StoredRun> runs = new ArrayList<>(loadOriginalRuns());
        runs.addAll(loadRerunRuns());
        assertThat(runs).filteredOn(StoredRun::successful).hasSize(24);

        String report = markdown(scenarios, runs);
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report);
        System.out.println("Question evaluation report=" + REPORT);
    }

    private Map<String, Scenario> loadScenarios() throws Exception {
        try (var input = getClass().getResourceAsStream("/ai/quality/gemini-audience-analysis-cases.json")) {
            assertThat(input).isNotNull();
            Dataset dataset = objectMapper.readValue(input, Dataset.class);
            return dataset.scenarios().stream().limit(8)
                    .collect(Collectors.toMap(Scenario::scenarioId, scenario -> scenario));
        }
    }

    private List<StoredRun> loadOriginalRuns() throws Exception {
        List<String> lines = Files.readAllLines(ORIGINAL_CSV);
        List<String> headers = csvColumns(lines.getFirst());
        List<StoredRun> runs = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            List<String> values = csvColumns(line);
            Map<String, String> row = new HashMap<>();
            for (int index = 0; index < headers.size(); index++) row.put(headers.get(index), values.get(index));
            runs.add(new StoredRun(row.get("scenarioId"), Integer.parseInt(row.get("repetition")), row.get("status"),
                    topics(row.get("topics").isBlank() ? objectMapper.createArrayNode() : objectMapper.readTree(row.get("topics"))), decimal(row.get("pairwisePrecision")),
                    decimal(row.get("pairwiseRecall")), decimal(row.get("pairwiseF1"))));
        }
        return runs;
    }

    private List<StoredRun> loadRerunRuns() throws Exception {
        Map<String, StoredRun> runs = new HashMap<>();
        for (String line : Files.readAllLines(RERUN_MARKDOWN)) {
            Matcher status = RERUN_STATUS.matcher(line);
            if (status.matches()) {
                String key = key(status.group(1), Integer.parseInt(status.group(2)));
                runs.put(key, new StoredRun(status.group(1), Integer.parseInt(status.group(2)), status.group(3).trim(),
                        new ArrayList<>(), decimal(status.group(4)), decimal(status.group(5)), decimal(status.group(6).trim())));
                continue;
            }
            Matcher topic = RERUN_TOPIC.matcher(line);
            if (topic.matches()) {
                StoredRun run = runs.get(key(topic.group(1), Integer.parseInt(topic.group(2))));
                assertThat(run).isNotNull();
                run.topics().add(new AudienceAnalysisQuestionTypeEvaluator.ActualTopic(topic.group(3), ids(topic.group(4))));
            }
        }
        return List.copyOf(runs.values());
    }

    private String markdown(Map<String, Scenario> scenarios, List<StoredRun> runs) {
        StringBuilder report = new StringBuilder("# Gemini 기존 8개 Fixture 질문 탐지·유형 분류 재평가\n\n")
                .append("- 외부 Gemini 호출 없이 기존 원본 CSV와 실패 표본 재실행 보고서의 24개 성공 응답만 재사용했다.\n")
                .append("- 질문 포착률: 사람이 QUESTION으로 라벨링한 ID가 어떤 주제 그룹에든 포함된 비율이다.\n")
                .append("- 질문 유형 분류 정확도: 질문 ID가 포함된 그룹 category가 QUESTION 또는 MIXED인 비율이다. 그룹 단위 category의 한계로, 혼합 그룹의 개별 발화 유형을 완전히 판정하지는 못한다.\n")
                .append("- questionMessageIds가 빈 fixture는 ‘질문 없음’으로 라벨링된 것이며, N/A로 표시한다.\n\n")
                .append("| 시나리오 | 질문 라벨 | 유효 응답 | 그룹화 P/R/F1 | 질문 포착률 | 질문 유형 분류 정확도 |\n")
                .append("|---|---|---:|---|---:|---:|\n");
        for (Scenario scenario : scenarios.values().stream().sorted(java.util.Comparator.comparing(Scenario::scenarioId)).toList()) {
            List<StoredRun> success = runs.stream().filter(run -> run.successful() && run.scenarioId().equals(scenario.scenarioId())).toList();
            List<AudienceAnalysisQuestionTypeEvaluator.QuestionEvaluation> evaluations = success.stream()
                    .map(run -> evaluator.evaluate(scenario.questionMessageIds(), run.topics())).toList();
            report.append("| ").append(scenario.scenarioId()).append(" | ").append(scenario.questionMessageIds()).append(" | ")
                    .append(success.size()).append(" | ").append(avg(success, StoredRun::precision)).append("/")
                    .append(avg(success, StoredRun::recall)).append("/").append(avg(success, StoredRun::f1)).append(" | ")
                    .append(questionMetric(evaluations, true)).append(" | ").append(questionMetric(evaluations, false)).append(" |\n");
        }
        report.append("\n## 사람 검토 구분\n\n")
                .append("- 주제 그룹화: Pairwise F1로 측정한다.\n")
                .append("- 질문 메시지 포착: sourceMessageIds 포함 여부만 측정하며, 질문으로 정확히 분류됐다는 뜻은 아니다.\n")
                .append("- 질문 유형 분류: QUESTION/MIXED category 여부를 별도 측정한다.\n")
                .append("- 요약 충실도·허위 주장: 기존 품질 보고서의 stance/must-not-claim을 사람이 검토해야 하며 자동 합격으로 처리하지 않는다.\n");
        return report.toString();
    }

    private String questionMetric(List<AudienceAnalysisQuestionTypeEvaluator.QuestionEvaluation> values, boolean capture) {
        if (values.isEmpty() || values.getFirst().notApplicable()) return "N/A";
        double value = values.stream().mapToDouble(item -> capture ? item.captureRecall() : item.categoryAccuracyAmongLabelledQuestions()).average().orElseThrow();
        return String.format("%.3f", value);
    }

    private List<AudienceAnalysisQuestionTypeEvaluator.ActualTopic> topics(JsonNode topics) {
        List<AudienceAnalysisQuestionTypeEvaluator.ActualTopic> result = new ArrayList<>();
        for (JsonNode topic : topics) result.add(new AudienceAnalysisQuestionTypeEvaluator.ActualTopic(
                topic.path("category").asText(), ids(topic.path("sourceMessageIds"))));
        return result;
    }

    private List<Long> ids(JsonNode ids) {
        List<Long> result = new ArrayList<>();
        ids.forEach(node -> result.add(node.asLong()));
        return result;
    }

    private List<Long> ids(String ids) {
        if (ids.isBlank()) return List.of();
        return java.util.Arrays.stream(ids.split(",")).map(String::trim).filter(value -> !value.isEmpty()).map(Long::parseLong).toList();
    }

    private List<String> csvColumns(String line) {
        List<String> columns = new ArrayList<>(); StringBuilder current = new StringBuilder(); boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') { if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') { current.append('"'); index++; } else quoted = !quoted; }
            else if (character == ',' && !quoted) { columns.add(current.toString()); current.setLength(0); }
            else current.append(character);
        }
        columns.add(current.toString()); return columns;
    }

    private Double decimal(String value) { return value == null || value.isBlank() ? null : Double.valueOf(value); }
    private String avg(List<StoredRun> runs, java.util.function.Function<StoredRun, Double> getter) {
        List<Double> values = runs.stream().map(getter).filter(java.util.Objects::nonNull).toList();
        return values.isEmpty() ? "N/A" : String.format("%.3f", values.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
    }
    private String key(String scenarioId, int repetition) { return scenarioId + "#" + repetition; }

    private record StoredRun(String scenarioId, int repetition, String status, List<AudienceAnalysisQuestionTypeEvaluator.ActualTopic> topics, Double precision, Double recall, Double f1) { boolean successful() { return "OK".equals(status); } }
    @JsonIgnoreProperties(ignoreUnknown = true) private record Dataset(List<Scenario> scenarios) { }
    @JsonIgnoreProperties(ignoreUnknown = true) private record Scenario(String scenarioId, List<Long> questionMessageIds, List<MessageLabel> messageLabels) { Scenario { questionMessageIds = questionMessageIds == null ? List.of() : List.copyOf(questionMessageIds); messageLabels = messageLabels == null ? List.of() : List.copyOf(messageLabels); } }
    private record MessageLabel(long messageId, String type, String note) { }
}
