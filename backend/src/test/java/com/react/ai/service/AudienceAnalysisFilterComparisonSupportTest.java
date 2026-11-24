package com.react.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.ai.domain.TopicCategory;
import com.react.ai.dto.AiAnalysisResult;
import com.react.ai.dto.AiTopicResult;
import com.react.ai.dto.AudienceAnalysisInputMessage;
import com.react.ai.dto.AudienceAnalysisLlmResponse;
import com.react.ai.dto.AudienceMessage;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static com.react.ai.service.AudienceAnalysisFilterComparisonSupport.SourceIdError.DUPLICATE_MESSAGE_ID;
import static com.react.ai.service.AudienceAnalysisFilterComparisonSupport.SourceIdError.FILTERED_OR_NON_REPRESENTATIVE_ID;
import static com.react.ai.service.AudienceAnalysisFilterComparisonSupport.SourceIdError.UNKNOWN_MESSAGE_ID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AudienceAnalysisFilterComparisonSupportTest {
    private static final Set<String> EXISTING_EIGHT_FIXTURE_IDS = Set.of(
            "similar-redis-questions",
            "mixed-question-opinion-request",
            "opposing-architecture-opinions",
            "concrete-request",
            "confusion-about-isolation",
            "small-unrelated-chat",
            "meaningless-chat-only",
            "boundary-multiple-topics-opposition-and-injection"
    );

    @Test
    void 빈_전처리_입력은_Gemini_호출을_생략한다() {
        assertThat(AudienceAnalysisFilterComparisonSupport.shouldCallGemini(List.of())).isFalse();
        assertThat(AudienceAnalysisFilterComparisonSupport.shouldCallGemini(null)).isFalse();
        assertThat(AudienceAnalysisFilterComparisonSupport.shouldCallGemini(
                List.of(new AudienceAnalysisInputMessage(1L, "질문", 1)))).isTrue();
    }

    @Test
    void Mock_LLM으로_전처리_전후_입력을_비교하고_복원한_원본_ID를_확인한다() {
        AudienceMessagePreprocessor preprocessor = new AudienceMessagePreprocessor();
        List<AudienceAnalysisInputMessage> before = List.of(
                new AudienceAnalysisInputMessage(1L, "ㄷㄷ", 1),
                new AudienceAnalysisInputMessage(2L, "Redis는 왜 쓰나요?", 1),
                new AudienceAnalysisInputMessage(3L, "Redis는 왜 쓰나요?", 1));
        var after = preprocessor.preprocess(List.of(
                message(1L, "ㄷㄷ"), message(2L, "Redis는 왜 쓰나요?"), message(3L, "Redis는 왜 쓰나요?")));
        AudienceAnalysisLlmClient client = mock(AudienceAnalysisLlmClient.class);
        when(client.analyze(anyList())).thenReturn(
                new AudienceAnalysisLlmResponse(
                        "{\"topics\":[{\"topic\":\"Redis\",\"summary\":\"질문\",\"category\":\"QUESTION\",\"sourceMessageIds\":[2,3]}]}",
                        "mock", new AudienceAnalysisLlmResponse.Usage(12L, 4L, 16L)),
                new AudienceAnalysisLlmResponse(
                        "{\"topics\":[{\"topic\":\"Redis\",\"summary\":\"질문\",\"category\":\"QUESTION\",\"sourceMessageIds\":[2]}]}",
                        "mock", new AudienceAnalysisLlmResponse.Usage(8L, 3L, 11L)));

        var beforeCall = AudienceAnalysisFilterComparisonSupport.invoke(client, before);
        var afterCall = AudienceAnalysisFilterComparisonSupport.invoke(client, after.inputMessages());
        AiAnalysisJsonParser parser = new AiAnalysisJsonParser(new ObjectMapper());
        AiAnalysisResult beforeExpanded = AudienceAnalysisFilterComparisonSupport.expandSourceMessageIds(
                parser.parse(beforeCall.response().content()), Map.of(1L, List.of(1L), 2L, List.of(2L), 3L, List.of(3L)),
                List.of(1L, 2L, 3L));
        AiAnalysisResult restored = AudienceAnalysisFilterComparisonSupport.expandSourceMessageIds(
                parser.parse(afterCall.response().content()), after.originalMessageIdsByRepresentative(), List.of(1L, 2L, 3L));
        var evaluator = new AudienceAnalysisClusteringEvaluator();
        var beforeScore = evaluator.evaluate(List.of(1L, 2L, 3L), List.of(List.of(2L, 3L)),
                beforeExpanded.topics().stream().map(AiTopicResult::sourceMessageIds).toList());
        var afterScore = evaluator.evaluate(List.of(1L, 2L, 3L), List.of(List.of(2L, 3L)),
                restored.topics().stream().map(AiTopicResult::sourceMessageIds).toList());

        assertThat(beforeCall.attempted()).isTrue();
        assertThat(afterCall.attempted()).isTrue();
        assertThat(after.inputMessages()).containsExactly(new AudienceAnalysisInputMessage(2L, "Redis는 왜 쓰나요?", 2));
        assertThat(restored.topics().getFirst().sourceMessageIds()).containsExactly(2L, 3L);
        assertThat(beforeScore.f1()).isEqualTo(1.0);
        assertThat(afterScore.f1()).isEqualTo(beforeScore.f1());
        verify(client, times(2)).analyze(anyList());
    }

    @Test
    void Mock_LLM은_전처리_후_빈_입력에서_호출되지_않는다() {
        AudienceAnalysisLlmClient client = mock(AudienceAnalysisLlmClient.class);
        var invocation = AudienceAnalysisFilterComparisonSupport.invoke(client, List.of());

        assertThat(invocation.attempted()).isFalse();
        assertThat(invocation.response()).isNull();
        verifyNoInteractions(client);
    }

    @Test
    void 알려지지_않은_ID와_필터링된_ID는_각각_명시적_실패가_된다() {
        Map<Long, List<Long>> representatives = Map.of(2L, List.of(2L, 3L));

        assertSourceIdFailure(result(999L), representatives, List.of(1L, 2L, 3L), UNKNOWN_MESSAGE_ID);
        assertSourceIdFailure(result(1L), representatives, List.of(1L, 2L, 3L), FILTERED_OR_NON_REPRESENTATIVE_ID);

        var invalid = org.assertj.core.api.Assertions.catchThrowable(() ->
                AudienceAnalysisFilterComparisonSupport.expandSourceMessageIds(
                        result(999L), representatives, List.of(1L, 2L, 3L)));
        assertThat(AudienceAnalysisFilterComparisonSupport.sourceIdFailureStatus(
                (AudienceAnalysisFilterComparisonSupport.SourceMessageValidationException) invalid))
                .isEqualTo("SOURCE_ID_UNKNOWN_MESSAGE_ID");
    }

    @Test
    void 여러_주제에_대표_ID가_중복되면_명시적_실패가_된다() {
        AiAnalysisResult duplicate = new AiAnalysisResult(List.of(topic(2L), topic(2L)));

        assertSourceIdFailure(duplicate, Map.of(2L, List.of(2L)), List.of(2L), DUPLICATE_MESSAGE_ID);
    }

    @Test
    void 호출별_토큰과_지연을_집계하고_누락된_사용량의_합계는_알_수_없음으로_둔다() {
        AudienceAnalysisLlmClient client = mock(AudienceAnalysisLlmClient.class);
        when(client.analyze(anyList())).thenReturn(new AudienceAnalysisLlmResponse(
                "{}", "mock", new AudienceAnalysisLlmResponse.Usage(12L, 4L, 16L)));
        AtomicLong clock = new AtomicLong(1_000_000L);
        var invocation = AudienceAnalysisFilterComparisonSupport.invoke(client,
                List.of(new AudienceAnalysisInputMessage(1L, "질문", 1)), () -> clock.getAndAdd(5_000_000L));

        assertThat(invocation.elapsedMillis()).isEqualTo(5L);
        assertThat(AudienceAnalysisFilterComparisonSupport.sumKnown(List.of(12L, 8L))).isEqualTo(20L);
        assertThat(AudienceAnalysisFilterComparisonSupport.sumKnown(java.util.Arrays.asList(12L, null))).isNull();
        assertThat(AudienceAnalysisFilterComparisonSupport.averageMillis(List.of(10L, 20L, 30L))).isEqualTo(20.0);
        assertThat(AudienceAnalysisFilterComparisonSupport.p95Millis(List.of(10L, 20L, 30L))).isEqualTo(30L);
        assertThat(invocation.response().usage().promptTokenCount()).isEqualTo(12L);
        assertThat(invocation.response().usage().outputTokenCount()).isEqualTo(4L);
    }

    @Test
    void 질문_탐지_재현율은_질문_ID_중_결과에_포함된_ID의_비율이다() {
        assertThat(AudienceAnalysisFilterComparisonSupport.questionRecall(
                List.of(1L, 2L, 3L), List.of(
                        new AudienceAnalysisClusteringEvaluator.ActualTopic("a", "MIXED", "s", List.of(1L)),
                        new AudienceAnalysisClusteringEvaluator.ActualTopic("b", "OPINION", "s", List.of(2L, 3L)),
                        new AudienceAnalysisClusteringEvaluator.ActualTopic("c", "QUESTION", "s", List.of(4L)))))
                .isEqualTo(1.0 / 3.0);
        assertThat(AudienceAnalysisFilterComparisonSupport.questionRecall(List.of(), List.of())).isNull();
    }

    @Test
    void p95는_nearest_rank으로_계산하고_세_표본에서는_최댓값이다() {
        assertThat(AudienceAnalysisFilterComparisonSupport.p95Millis(List.of(30L, 10L, 20L))).isEqualTo(30L);
        assertThat(AudienceAnalysisFilterComparisonSupport.p95Millis(List.of())).isZero();
    }

    @Test
    void 기존_8개_fixture가_별도_실험_대상으로_유지되고_운영_전처리_후_호출_가능한_입력을_남긴다() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/ai/quality/gemini-audience-analysis-cases.json")) {
            assertThat(input).isNotNull();
            var scenarios = new ObjectMapper().readTree(input).path("scenarios");
            Set<String> actualIds = new java.util.HashSet<>();
            AudienceMessagePreprocessor preprocessor = new AudienceMessagePreprocessor();
            for (var scenario : scenarios) {
                String scenarioId = scenario.path("scenarioId").asText();
                if (!EXISTING_EIGHT_FIXTURE_IDS.contains(scenarioId)) {
                    continue;
                }
                actualIds.add(scenarioId);
                List<AudienceMessage> messages = new java.util.ArrayList<>();
                for (var message : scenario.path("messages")) {
                    messages.add(message(message.path("id").asLong(), message.path("content").asText()));
                }
                assertThat(AudienceAnalysisFilterComparisonSupport.shouldCallGemini(
                        preprocessor.preprocess(messages).inputMessages()))
                        .as("%s 전처리 후 입력", scenarioId)
                        .isTrue();
            }
            assertThat(actualIds).containsExactlyInAnyOrderElementsOf(EXISTING_EIGHT_FIXTURE_IDS);
        }
    }

    private AudienceMessage message(long id, String content) {
        return new AudienceMessage(id, 1L, content, LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    private AiAnalysisResult result(long representativeId) {
        return new AiAnalysisResult(List.of(topic(representativeId)));
    }

    private AiTopicResult topic(long representativeId) {
        return new AiTopicResult("질문", "요약", TopicCategory.QUESTION, List.of(representativeId));
    }

    private void assertSourceIdFailure(AiAnalysisResult result, Map<Long, List<Long>> map,
                                       List<Long> allOriginalIds,
                                       AudienceAnalysisFilterComparisonSupport.SourceIdError expected) {
        assertThatThrownBy(() -> AudienceAnalysisFilterComparisonSupport.expandSourceMessageIds(
                result, map, allOriginalIds))
                .isInstanceOf(AudienceAnalysisFilterComparisonSupport.SourceMessageValidationException.class)
                .extracting(exception -> ((AudienceAnalysisFilterComparisonSupport.SourceMessageValidationException)
                        exception).error())
                .isEqualTo(expected);
    }
}
