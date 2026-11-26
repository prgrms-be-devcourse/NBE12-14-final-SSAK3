package com.react.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.report.service.BackgroundReportClient;
import com.react.report.service.OpenAiReportOverviewClient;
import com.react.report.service.ReportGenerationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
    private static final String ATTEMPT = "e89f95e6-3806-4a2f-b0ed-481cd690ed35";
    private static final String RESPONSES = "https://api.openai.com/v1/responses";
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
    void background_접수에_로컬_시도키를_포함하고_조회와_삭제는_같은_ID에만_보낸다() {
        var background = client.background();
        server.expect(requestTo(RESPONSES)).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.store").value(true)).andExpect(jsonPath("$.background").value(true))
                .andExpect(jsonPath("$.metadata.report_attempt").value(ATTEMPT))
                .andExpect(jsonPath("$.text.format.strict").value(true))
                .andRespond(withSuccess("{\"id\":\"resp_abc\",\"status\":\"queued\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(RESPONSES + "/resp_abc")).andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer unit-test-key"))
                .andRespond(withSuccess("""
                        {"id":"resp_abc","status":"completed","output":[{"type":"message",
                        "content":[{"type":"output_text","text":"{}"}]}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(RESPONSES + "/resp_abc")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{\"id\":\"resp_abc\",\"deleted\":true}", MediaType.APPLICATION_JSON));

        assertThat(background.submit(input, ATTEMPT).path("id").asText()).isEqualTo("resp_abc");
        assertThat(background.draft(background.retrieve("resp_abc"))).isEqualTo(mapper.createObjectNode());
        background.delete("resp_abc");
        server.verify();
    }

    @Test
    void 접수_네트워크실패와_5xx_응답은_원문_없이_미확인으로_남긴다() {
        server.expect(requestTo(RESPONSES)).andRespond(withException(new SocketTimeoutException("private error")));
        server.expect(requestTo(RESPONSES)).andRespond(withStatus(HttpStatus.REQUEST_TIMEOUT).body("private error"));
        server.expect(requestTo(RESPONSES)).andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("private error"));
        for (String diagnostic : new String[]{"OPENAI_TIMEOUT", "OPENAI_HTTP_408", "OPENAI_HTTP_502"}) {
            assertThatThrownBy(() -> client.background().submit(input, ATTEMPT))
                    .isInstanceOfSatisfying(BackgroundReportClient.SubmissionUnknown.class, error -> {
                        assertThat(error.diagnosticCode()).isEqualTo(diagnostic);
                        assertThat(error.getCause()).isNull();
                        assertThat(error.getMessage()).doesNotContain("private", "unit-test-key");
                    });
        }
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "{\"id\":\"resp_a/b\"}", "{\"id\":\"resp_x\"} {}", "{broken"})
    void 유효한_접수증이_없는_성공_응답은_미확인이다(String body) {
        server.expect(requestTo(RESPONSES)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.background().submit(input, ATTEMPT))
                .isInstanceOf(BackgroundReportClient.SubmissionUnknown.class);
        server.verify();
    }

    @Test
    void 접수_거절과_기존_응답의_404를_구분한다() {
        server.expect(requestTo(RESPONSES)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "30"));
        server.expect(requestTo(RESPONSES + "/resp_abc")).andRespond(withResourceNotFound());
        server.expect(requestTo(RESPONSES + "/resp_abc")).andRespond(withResourceNotFound());
        var background = client.background();
        assertThatThrownBy(() -> background.submit(input, ATTEMPT)).isInstanceOfSatisfying(ReportGenerationException.class,
                failure -> assertThat(failure.diagnosticCode()).isEqualTo("OPENAI_HTTP_429_RATE_LIMIT"));
        assertThatThrownBy(() -> background.retrieve("resp_abc")).isInstanceOf(BackgroundReportClient.NotFound.class);
        assertThatThrownBy(() -> background.delete("resp_abc")).isInstanceOf(BackgroundReportClient.NotFound.class);
        server.verify();
    }

    @Test
    void 다른_응답_ID와_확인되지_않은_삭제는_거부한다() {
        var background = client.background();
        server.expect(requestTo(RESPONSES + "/resp_abc"))
                .andRespond(withSuccess("{\"id\":\"resp_other\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(RESPONSES + "/resp_abc"))
                .andRespond(withSuccess("{\"id\":\"resp_abc\",\"deleted\":\"true\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> background.retrieve("resp_abc")).isInstanceOf(ReportGenerationException.class);
        assertThatThrownBy(() -> background.delete("resp_abc")).isInstanceOf(ReportGenerationException.class);
        server.verify();
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
