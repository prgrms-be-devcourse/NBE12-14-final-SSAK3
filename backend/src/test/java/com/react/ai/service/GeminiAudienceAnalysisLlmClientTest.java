package com.react.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.ai.config.GeminiAudienceAnalysisProperties;
import com.react.ai.dto.AudienceMessage;
import com.react.ai.exception.GeminiAudienceAnalysisException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GeminiAudienceAnalysisLlmClientTest {
    private static final String MODEL = "gemini-3.5-flash-lite";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void Gemini_응답을_기존_LLM_응답_DTO로_변환하고_구조화_JSON_요청을_보낸다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://gemini.test/v1beta/models/" + MODEL + ":generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().string(allOf(
                        containsString("\\\"messageId\\\":1001"),
                        containsString("질문 내용"),
                        containsString("\"responseMimeType\":\"application/json\""),
                        containsString("\"responseJsonSchema\""),
                        containsString("\"maxOutputTokens\":2048")
                )))
                .andExpect(jsonPath("$.generationConfig.responseJsonSchema.type").value("object"))
                .andExpect(jsonPath("$.generationConfig.responseJsonSchema.properties.topics.type").value("array"))
                .andExpect(jsonPath("$.generationConfig.responseJsonSchema.properties.topics.items.type").value("object"))
                .andExpect(jsonPath("$.generationConfig.responseJsonSchema.properties.topics.items.properties.topic.type").value("string"))
                .andExpect(jsonPath("$.generationConfig.responseJsonSchema.properties.topics.items.properties.sourceMessageIds.items.type").value("integer"))
                .andRespond(withSuccess(geminiResponse("{\"topics\":[]}"), MediaType.APPLICATION_JSON));

        var result = client(builder.build(), properties()).analyze(List.of(message(1001L, "질문 내용")));

        assertThat(result.modelName()).isEqualTo(MODEL);
        assertThat(result.content()).isEqualTo("{\"topics\":[]}");
        server.verify();
    }

    @Test
    void 인증_요청제한_서버오류는_상태만_포함한_예외로_변환한다() {
        assertHttpFailure(HttpStatus.UNAUTHORIZED, 401);
        assertHttpFailure(HttpStatus.FORBIDDEN, 403);
        assertHttpFailure(HttpStatus.TOO_MANY_REQUESTS, 429);
        assertHttpFailure(HttpStatus.INTERNAL_SERVER_ERROR, 500);
    }

    @Test
    void 연결_응답_타임아웃과_비정상_응답은_민감정보_없는_예외가_된다() {
        ClientHttpRequestFactory failingFactory = (uri, method) -> {
            throw new SocketTimeoutException("read timed out");
        };
        var networkClient = new GeminiAudienceAnalysisLlmClient(
                RestClient.builder().requestFactory(failingFactory).build(), objectMapper, properties());

        assertSensitiveInformationIsHidden(() -> networkClient.analyze(List.of(message(1L, "비밀 원문"))));

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://gemini.test/v1beta/models/" + MODEL + ":generateContent"))
                .andRespond(withSuccess("{\"candidates\":[]}", MediaType.APPLICATION_JSON));
        var malformedClient = client(builder.build(), properties());

        assertSensitiveInformationIsHidden(() -> malformedClient.analyze(List.of(message(1L, "비밀 원문"))));
    }

    @Test
    void MAX_TOKENS_등_정상_종료가_아닌_응답은_결과로_사용하지_않는다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://gemini.test/v1beta/models/" + MODEL + ":generateContent"))
                .andRespond(withSuccess(geminiResponse("{\"topics\":[]}", "MAX_TOKENS"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client(builder.build(), properties()).analyze(List.of(message(1L, "질문"))))
                .isInstanceOf(GeminiAudienceAnalysisException.class)
                .hasMessage("Gemini 분석 응답이 정상 종료되지 않았습니다.");
    }

    @Test
    void 입력과_응답_크기_상한을_초과하면_호출하지_않는다() {
        var strictProperties = new GeminiAudienceAnalysisProperties("test-key", MODEL,
                "https://gemini.test/v1beta", Duration.ofSeconds(1), Duration.ofSeconds(1),
                1, 3, 100, 10);
        var client = client(RestClient.builder().build(), strictProperties);

        assertThatThrownBy(() -> client.analyze(List.of(message(1L, "길어진 입력"))))
                .isInstanceOf(GeminiAudienceAnalysisException.class)
                .hasMessageContaining("입력 크기");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://gemini.test/v1beta/models/" + MODEL + ":generateContent"))
                .andRespond(withSuccess(geminiResponse("{\"topics\":[{\"topic\":\"긴 주제\"}]}"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client(builder.build(), strictProperties).analyze(List.of(message(1L, "x"))))
                .isInstanceOf(GeminiAudienceAnalysisException.class)
                .hasMessageContaining("응답 본문");
    }

    @Test
    void API_키_또는_모델명이_비정상이면_외부_호출_전에_거절한다() {
        var missingKey = new GeminiAudienceAnalysisProperties("", MODEL, "https://gemini.test/v1beta",
                Duration.ofSeconds(1), Duration.ofSeconds(1), 100, 32_000, 2_048, 128_000);
        var invalidModel = new GeminiAudienceAnalysisProperties("test-key", "invalid/model", "https://gemini.test/v1beta",
                Duration.ofSeconds(1), Duration.ofSeconds(1), 100, 32_000, 2_048, 128_000);

        assertThatThrownBy(() -> client(RestClient.builder().build(), missingKey).analyze(List.of(message(1L, "질문"))))
                .isInstanceOf(GeminiAudienceAnalysisException.class)
                .hasMessage("Gemini 분석 설정이 올바르지 않습니다.");
        assertThatThrownBy(() -> client(RestClient.builder().build(), invalidModel).analyze(List.of(message(1L, "질문"))))
                .isInstanceOf(GeminiAudienceAnalysisException.class)
                .hasMessage("Gemini 분석 설정이 올바르지 않습니다.");
    }

    private void assertHttpFailure(HttpStatus status, int expectedCode) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://gemini.test/v1beta/models/" + MODEL + ":generateContent"))
                .andRespond(withStatus(status).body("provider detail that must not escape"));

        assertThatThrownBy(() -> client(builder.build(), properties()).analyze(List.of(message(1L, "비밀 원문"))))
                .isInstanceOf(GeminiAudienceAnalysisException.class)
                .hasMessage("Gemini API가 HTTP " + expectedCode + "을 반환했습니다.")
                .satisfies(error -> {
                    assertThat(error.getMessage()).doesNotContain("test-key", "비밀 원문", "provider detail");
                    assertThat(error.getCause()).isNull();
                });
    }

    private void assertSensitiveInformationIsHidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(GeminiAudienceAnalysisException.class)
                .satisfies(error -> {
                    assertThat(error.getMessage()).doesNotContain("test-key", "비밀 원문", "provider detail");
                    assertThat(error.getCause()).isNull();
                });
    }

    private GeminiAudienceAnalysisLlmClient client(RestClient restClient, GeminiAudienceAnalysisProperties properties) {
        return new GeminiAudienceAnalysisLlmClient(restClient, objectMapper, properties);
    }

    private GeminiAudienceAnalysisProperties properties() {
        return new GeminiAudienceAnalysisProperties("test-key", MODEL, "https://gemini.test/v1beta",
                Duration.ofSeconds(1), Duration.ofSeconds(1), 100, 32_000, 2_048, 128_000);
    }

    private AudienceMessage message(long id, String content) {
        return new AudienceMessage(id, 1L, content, LocalDateTime.of(2026, 11, 17, 10, 0));
    }

    private String geminiResponse(String text) {
        return geminiResponse(text, "STOP");
    }

    private String geminiResponse(String text, String finishReason) {
        return """
                {"candidates":[{"finishReason":%s,"content":{"parts":[{"text":%s}]}}]}
                """.formatted(toJsonString(finishReason), toJsonString(text));
    }

    private String toJsonString(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
