package com.react.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.global.config.SecurityConfig;
import com.react.report.controller.OpenAiWebhookController;
import com.react.report.repository.ReportAttemptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = OpenAiWebhookController.class,
        properties = {"app.ai.report.webhook-secret=whsec_MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "app.ai.report.background-enabled=true"})
@Import(SecurityConfig.class)
class OpenAiWebhookControllerTest {
    private static final String PATH = "/api/v1/webhooks/openai";
    private static final String EVENT = """
            {"type":"response.completed", "data":{"id":"resp_123"}}
            """;
    @Autowired MockMvc mvc;
    @MockitoBean ReportAttemptRepository attempts;

    @ParameterizedTest
    @ValueSource(strings = {"completed", "cancelled", "failed", "incomplete"})
    void 세션과_CSRF_없이_서명된_종료_이벤트의_응답_ID만_접수한다(String state) throws Exception {
        String event = EVENT.replace("completed", state);
        mvc.perform(signed(event, Instant.now().getEpochSecond()))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(attempts).enqueueWebhook("resp_123");
    }

    @Test
    void 원본_바이트가_다르거나_만료되거나_미래의_서명은_거절한다() throws Exception {
        long now = Instant.now().getEpochSecond();
        mvc.perform(signed(EVENT, now).content(EVENT.trim())).andExpect(status().isUnauthorized());
        mvc.perform(signed(EVENT, now - 600)).andExpect(status().isUnauthorized());
        mvc.perform(signed(EVENT, now + 600)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).content(EVENT)).andExpect(status().isUnauthorized());
        verifyNoInteractions(attempts);
    }

    @Test
    void 유효한_서명에서도_모르는_이벤트를_무시하고_잘못된_ID와_JSON은_거절한다() throws Exception {
        long now = Instant.now().getEpochSecond();
        mvc.perform(signed(EVENT.replace("response.completed", "batch.completed"), now)).andExpect(status().isNoContent());
        mvc.perform(signed(EVENT.replace("resp_123", "invalid/id"), now)).andExpect(status().isBadRequest());
        mvc.perform(signed(EVENT + "{}", now)).andExpect(status().isBadRequest());
        mvc.perform(signed("{}", now)).andExpect(status().isBadRequest());
        verifyNoInteractions(attempts);
    }

    @Test
    void 본문_크기와_미설정_서명을_검사하고_다른_경로에는_CSRF를_유지한다() throws Exception {
        mvc.perform(post(PATH).content(new byte[65537])).andExpect(status().isPayloadTooLarge());
        var chunked = new MockHttpServletRequest() {
            @Override public long getContentLengthLong() { return -1; }
        };
        chunked.setContent(new byte[65537]);
        var mapper = new ObjectMapper();
        var configured = new OpenAiWebhookController(attempts, mapper,
                "whsec_MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", true);
        assertThat(configured.receive(chunked).getStatusCode().value()).isEqualTo(413);
        var unset = new OpenAiWebhookController(attempts, mapper, "", false);
        assertThat(unset.receive(chunked).getStatusCode().value()).isEqualTo(503);
        mvc.perform(post(PATH + "/other")).andExpect(status().isForbidden());
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        verifyNoInteractions(attempts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "fixture-private-secret", "whsec_%%%%", "whsec_"})
    void background_활성시_키가_잘못되면_원문_없이_시작을_거부한다(String secret) {
        new ApplicationContextRunner().withUserConfiguration(OpenAiWebhookController.class)
                .withBean(ReportAttemptRepository.class, () -> attempts)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("app.ai.report.background-enabled=true", "app.ai.report.webhook-secret=" + secret)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Background reports require a valid OPENAI_REPORT_WEBHOOK_SECRET");
                });
    }

    @Test
    void 내구성_있는_접수_실패는_공급자가_재전송할_수_있게_오류를_반환한다() throws Exception {
        doThrow(new DataAccessResourceFailureException("private database message")).when(attempts).enqueueWebhook("resp_123");
        mvc.perform(signed(EVENT, Instant.now().getEpochSecond())).andExpect(status().isServiceUnavailable())
                .andExpect(content().string(""));
    }

    private MockHttpServletRequestBuilder signed(String body, long seconds) throws Exception {
        String eventId = "msg_fixture";
        var hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signature = hmac.doFinal((eventId + "." + seconds + "." + body).getBytes(StandardCharsets.UTF_8));
        return post(PATH).contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8))
                .header("webhook-id", eventId).header("webhook-timestamp", seconds)
                .header("webhook-signature", "v1,invalid v1," + Base64.getEncoder().encodeToString(signature));
    }
}
