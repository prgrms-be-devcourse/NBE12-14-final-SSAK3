package com.react.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.report.service.OpenAiReportOverviewClient;
import com.react.report.service.ReportGenerationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;

import static com.react.report.service.ReportGenerationException.Reason.INVALID_REPORT_FORMAT;
import static com.react.report.service.ReportGenerationException.Reason.LLM_REQUEST_FAILED;
import static com.react.report.service.ReportGenerationException.Reason.LLM_TEMPORARY_FAILURE;
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

    @ParameterizedTest
    @CsvSource({"429,LLM_REQUEST_FAILED,OPENAI_HTTP_429_UNCLASSIFIED", "503,LLM_TEMPORARY_FAILURE,OPENAI_HTTP_503",
            "401,LLM_REQUEST_FAILED,OPENAI_HTTP_401", "403,LLM_REQUEST_FAILED,OPENAI_HTTP_403",
            "400,LLM_REQUEST_FAILED,OPENAI_HTTP_400"})
    void HTTP_오류는_원인을_분류하고_제공자_응답_노출이나_자동_재호출_없이_실패한다(
            int status, ReportGenerationException.Reason expected, String diagnostic) {
        server.expect(requestTo("https://api.openai.com/v1/responses"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).body("private provider error"));
        assertThatThrownBy(() -> client.generate(input)).isInstanceOfSatisfying(ReportGenerationException.class,
                ex -> {
                    assertThat(ex.reason()).isEqualTo(expected);
                    assertThat(ex.diagnosticCode()).isEqualTo(diagnostic);
                    assertThat(ex.getMessage()).doesNotContain("private", "unit-test-key");
                });
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({
            "rate_limit_exceeded,requests,,LLM_TEMPORARY_FAILURE,RATE_LIMIT",
            "slow_down,rate_limit_error,5,LLM_TEMPORARY_FAILURE,RATE_LIMIT",
            "unknown,tokens,,LLM_TEMPORARY_FAILURE,RATE_LIMIT",
            "unknown,unknown,15,LLM_TEMPORARY_FAILURE,RATE_LIMIT",
            "unknown,unknown,tomorrow,LLM_REQUEST_FAILED,UNCLASSIFIED",
            "insufficient_quota,insufficient_quota,5,LLM_REQUEST_FAILED,QUOTA",
            "credit_balance_exhausted,unknown,5,LLM_REQUEST_FAILED,QUOTA",
            "organization_spend_limit_exceeded,unknown,,LLM_REQUEST_FAILED,QUOTA",
            "project_spend_limit_exceeded,unknown,,LLM_REQUEST_FAILED,QUOTA",
            "organization_usage_limit_exceeded,unknown,,LLM_REQUEST_FAILED,QUOTA",
            "unknown,insufficient_quota,5,LLM_REQUEST_FAILED,QUOTA",
            "untrusted-code,untrusted-type,,LLM_REQUEST_FAILED,UNCLASSIFIED"
    })
    void HTTP_429는_속도_제한만_재시도할_수_있다(
            String code, String type, String retryAfter, ReportGenerationException.Reason expected, String diagnostic) {
        var response = withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":\"" + code + "\",\"type\":\"" + type + "\",\"message\":\"private\"}}");
        if (retryAfter != null) response.header(HttpHeaders.RETRY_AFTER, retryAfter);
        server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(response);
        assertThatThrownBy(() -> client.generate(input)).isInstanceOfSatisfying(ReportGenerationException.class,
                ex -> {
                    assertThat(ex.reason()).isEqualTo(expected);
                    assertThat(ex.diagnosticCode()).isEqualTo("OPENAI_HTTP_429_" + diagnostic);
                    assertThat(ex.getMessage()).doesNotContain("private", "unit-test-key");
                });
        server.verify();
    }

    @Test
    void 네트워크와_타임아웃만_일시적_장애로_분류한다() {
        for (var failure : new java.io.IOException[]{
                new ConnectException(), new SocketTimeoutException(), new InterruptedIOException()}) {
            server.reset();
            server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(withException(failure));
            var expected = failure.getClass() == InterruptedIOException.class ? LLM_REQUEST_FAILED : LLM_TEMPORARY_FAILURE;
            var diagnostic = switch (failure) {
                case SocketTimeoutException ignored -> "OPENAI_TIMEOUT";
                case ConnectException ignored -> "OPENAI_NETWORK";
                default -> "OPENAI_TRANSPORT";
            };
            assertThatThrownBy(() -> client.generate(input)).isInstanceOfSatisfying(ReportGenerationException.class,
                    ex -> {
                        assertThat(ex.reason()).isEqualTo(expected);
                        assertThat(ex.diagnosticCode()).isEqualTo(diagnostic);
                    });
            server.verify();
        }
    }

    @Test
    void 키나_모델이_없으면_외부_요청을_보내지_않고_누락_항목만_기록한다() {
        assertThatThrownBy(() -> client("").generate(input)).isInstanceOfSatisfying(ReportGenerationException.class,
                ex -> {
                    assertThat(ex.reason()).isEqualTo(LLM_REQUEST_FAILED);
                    assertThat(ex.diagnosticCode()).isEqualTo("OPENAI_API_KEY_MISSING");
                });
        var noModel = new OpenAiReportOverviewClient(builder.build(), mapper, "unit-test-key", "", "요약 지침", input);
        assertThatThrownBy(() -> noModel.generate(input)).isInstanceOfSatisfying(ReportGenerationException.class,
                ex -> {
                    assertThat(ex.reason()).isEqualTo(LLM_REQUEST_FAILED);
                    assertThat(ex.diagnosticCode()).isEqualTo("OPENAI_MODEL_MISSING");
                });
        server.verify();
    }

    @Test
    void 거절과_완료되지_않은_응답은_호출_실패로_분류한다() {
        assertResponseFailure("""
                {"status":"completed","output":[{"type":"message","content":[{"type":"refusal"}]}]}
                """, LLM_REQUEST_FAILED, "OPENAI_REFUSAL");
        server.reset();
        assertResponseFailure("""
                {"status":"incomplete","output":[]}
                """, LLM_REQUEST_FAILED, "OPENAI_INCOMPLETE_RESPONSE");
    }

    @Test
    void 완료_응답에_JSON이_두_개_있어도_첫_문서만_채택하지_않는다() {
        assertResponseFailure("""
                {"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"{} {}"}]}]}
                """, INVALID_REPORT_FORMAT, "INVALID_REPORT_FORMAT");
    }

    private void assertResponseFailure(String response, ReportGenerationException.Reason reason, String diagnostic) {
        server.expect(requestTo("https://api.openai.com/v1/responses"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.generate(input)).isInstanceOfSatisfying(ReportGenerationException.class,
                ex -> {
                    assertThat(ex.reason()).isEqualTo(reason);
                    assertThat(ex.diagnosticCode()).isEqualTo(diagnostic);
                });
        server.verify();
    }
}
