package com.react.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.report.service.OpenAiReportOverviewClient;
import com.react.report.service.ReportGenerationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static com.react.report.service.ReportGenerationException.Reason.INVALID_REPORT_FORMAT;
import static com.react.report.service.ReportGenerationException.Reason.LLM_REQUEST_FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OpenAiReportOverviewClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final JsonNode input = mapper.valueToTree(Map.of("roomId", 302));
    private final OpenAiReportOverviewClient client = client("unit-test-key");

    private OpenAiReportOverviewClient client(String key) {
        return new OpenAiReportOverviewClient(builder.build(), mapper, key, "gpt-6-luna", "요약 지침",
                mapper.valueToTree(Map.of("type", "object")));
    }

    @Test
    void 단일_Responses_요청에서_스키마를_지정하고_완료된_메시지의_초안만_읽는다() {
        server.expect(requestTo("https://api.openai.com/v1/responses")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer unit-test-key")).andExpect(jsonPath("$.store").value(false))
                .andExpect(jsonPath("$.model").value("gpt-6-luna")).andExpect(jsonPath("$.input").value(input.toString()))
                .andExpect(jsonPath("$.text.format.strict").value(true))
                .andExpect(jsonPath("$.text.format.schema.type").value("object"))
                .andRespond(withSuccess("""
                    {"status":"completed","output":[
                     {"type":"reasoning","summary":[]},
                     {"type":"message","content":[{"type":"output_text","text":"{\\"overview\\":{}}"}]}]}
                    """, MediaType.APPLICATION_JSON));
        assertThat(client.generate(input).has("overview")).isTrue();
        server.verify();
    }

    @Test
    void HTTP_오류는_제공자_응답_노출이나_자동_재호출_없이_실패한다() {
        server.expect(requestTo("https://api.openai.com/v1/responses"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("private provider error"));
        assertThatThrownBy(() -> client.generate(input)).isInstanceOfSatisfying(ReportGenerationException.class,
                ex -> {
                    assertThat(ex.reason()).isEqualTo(LLM_REQUEST_FAILED);
                    assertThat(ex.getMessage()).doesNotContain("private", "unit-test-key");
                });
        server.verify();
    }

    @Test
    void 키가_없으면_외부_요청을_보내지_않는다() {
        assertThatThrownBy(() -> client("").generate(input)).isInstanceOfSatisfying(ReportGenerationException.class,
                ex -> assertThat(ex.reason()).isEqualTo(LLM_REQUEST_FAILED));
        server.verify();
    }

    @Test
    void 거절과_완료되지_않은_응답은_호출_실패로_분류한다() {
        assertResponseFailure("""
                {"status":"completed","output":[{"type":"message","content":[{"type":"refusal"}]}]}
                """, LLM_REQUEST_FAILED);
        server.reset();
        assertResponseFailure("""
                {"status":"incomplete","output":[]}
                """, LLM_REQUEST_FAILED);
    }

    @Test
    void 완료_응답에_JSON이_두_개_있어도_첫_문서만_채택하지_않는다() {
        assertResponseFailure("""
                {"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"{} {}"}]}]}
                """, INVALID_REPORT_FORMAT);
    }

    private void assertResponseFailure(String response, ReportGenerationException.Reason reason) {
        server.expect(requestTo("https://api.openai.com/v1/responses"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.generate(input)).isInstanceOfSatisfying(ReportGenerationException.class,
                ex -> assertThat(ex.reason()).isEqualTo(reason));
        server.verify();
    }
}
