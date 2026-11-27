package com.react.ai.service;

import com.react.ai.config.GeminiAudienceAnalysisProperties;
import com.react.ai.dto.AiAnalysisResult;
import com.react.ai.dto.AudienceAnalysisInputMessage;
import com.react.ai.dto.AudienceAnalysisLlmResponse;
import com.react.ai.dto.AudienceMessage;
import com.react.ai.dto.PreprocessedAudienceMessages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Gemini 단일 요청의 입력 규모별 응답 시간을 확인하는 수동·opt-in 측정이다.
 * 한 번 실행하면 A~E를 각 한 번, 최대 다섯 번만 호출한다. 재시도 정책은 서비스 계층의
 * 책임이므로 이 측정은 재시도하지 않으며 retryCount=0으로 기록한다.
 * 실행: RUN_GEMINI_PERFORMANCE_MEASUREMENT=true ./gradlew test --rerun-tasks --tests
 * com.react.ai.service.GeminiAudienceAnalysisPerformanceMeasurementTest
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=none", "spring.task.scheduling.enabled=false"
})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "RUN_GEMINI_PERFORMANCE_MEASUREMENT", matches = "true")
class GeminiAudienceAnalysisPerformanceMeasurementTest {
    @Autowired AudienceAnalysisLlmClient llmClient;
    @Autowired AudienceMessagePreprocessor preprocessor;
    @Autowired AiAnalysisJsonParser parser;
    @Autowired GeminiAudienceAnalysisProperties properties;

    @Test
    void 입력_규모별_Gemini_단일_요청_응답시간을_측정한다() {
        assertThat(properties.apiKey()).as("GEMINI_API_KEY 설정").isNotBlank();
        assertThat(properties.model()).as("GEMINI_MODEL 설정").isNotBlank();
        System.out.println("Gemini performance measurement model=" + properties.model());
        System.out.println("GEMINI_API_KEY present=true");

        List<Scenario> scenarios = List.of(
                new Scenario("A", 10, 20, 50),
                new Scenario("B", 50, 50, 100),
                new Scenario("C", 100, 100, 200),
                new Scenario("D", 100, 200, 300),
                new Scenario("E", 100, 100, 200)
        );
        for (Scenario scenario : scenarios) {
            measure(scenario);
        }
    }

    private void measure(Scenario scenario) {
        PreprocessedAudienceMessages preprocessed = preprocessor.preprocess(messages(scenario));
        List<AudienceAnalysisInputMessage> input = preprocessed.inputMessages();
        int characters = input.stream().mapToInt(message -> message.content().length()).sum();
        assertThat(input).hasSize(scenario.count());
        assertThat(characters).isLessThanOrEqualTo(32_000);

        long started = System.nanoTime();
        AudienceAnalysisLlmResponse response = llmClient.analyze(input);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        AiAnalysisResult parsed = parser.parse(response.content());

        AudienceAnalysisLlmResponse.Usage usage = response.usage();
        System.out.printf("Gemini performance scenario=%s, sentMessages=%d, sentCharacters=%d, "
                        + "responseMs=%d, jsonValid=true, retryCount=0, success=true, "
                        + "promptTokens=%s, outputTokens=%s, totalTokens=%s, topicCount=%d%n",
                scenario.name(), input.size(), characters, elapsedMillis,
                usage == null ? "N/A" : usage.promptTokenCount(),
                usage == null ? "N/A" : usage.outputTokenCount(),
                usage == null ? "N/A" : usage.totalTokenCount(), parsed.topics().size());
    }

    private List<AudienceMessage> messages(Scenario scenario) {
        List<AudienceMessage> result = new ArrayList<>();
        String[] stems = {
                "Redis 캐시를 도입하면 조회 성능이 좋아질까요?",
                "작은 서비스에서는 운영 부담이 더 커질까 걱정됩니다.",
                "MySQL만으로 처리할 수 있는 범위가 궁금합니다.",
                "캐시 데이터가 오래되면 일관성은 어떻게 보장하나요?",
                "실제 장애 사례와 대응 방법을 예시로 설명해 주세요.",
                "지금 설명한 인덱스 조건을 다시 한번 보여주세요.",
                "비용이 늘어나는 기준을 비교해 보고 싶습니다.",
                "왜 이 구조가 필요한지 아직 이해가 잘 안 됩니다.",
                "도입 전에 측정해야 할 지표가 무엇인지 질문드립니다.",
                "대안으로 메시지 큐를 쓰는 경우와 차이가 있나요?"
        };
        for (int index = 0; index < scenario.count(); index++) {
            String content = boundedNaturalMessage(stems[index % stems.length], index + 1,
                    scenario.minLength(), scenario.maxLength());
            result.add(new AudienceMessage(index + 1L, 1L, content, LocalDateTime.of(2026, 1, 1, 10, 0)));
        }
        return result;
    }

    private String boundedNaturalMessage(String stem, int number, int minLength, int maxLength) {
        String text = stem + " 이번 발표의 사례 " + number + "에 적용할 때의 기준도 알려주세요.";
        String padding = " 현장 상황을 고려한 설명을 부탁드립니다.";
        while (text.length() < minLength) {
            text += padding;
        }
        if (text.length() > maxLength) {
            text = text.substring(0, maxLength);
        }
        assertThat(text.length()).isBetween(minLength, maxLength);
        return text;
    }

    private record Scenario(String name, int count, int minLength, int maxLength) {
    }
}
