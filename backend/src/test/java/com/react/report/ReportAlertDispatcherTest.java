package com.react.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.report.repository.ReportAlertRepository;
import com.react.report.repository.ReportAlertRepository.Alert;
import com.react.report.service.ReportAlertDispatcher;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportAlertDispatcherTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ReportAlertRepository alerts = mock(ReportAlertRepository.class);
    private final AtomicReference<UUID> lease = new AtomicReference<>();
    private final AtomicReference<String> payload = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();
    private HttpServer receiver;
    private String url;

    @BeforeEach
    void setup() throws Exception {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        url = "http://127.0.0.1:" + receiver.getAddress().getPort();
        when(alerts.claim(any())).thenAnswer(call -> {
            lease.set(call.getArgument(0));
            return List.of(new Alert(42L, "OPENAI_HTTP_503", false, Instant.parse("2026-11-26T00:00:00Z")),
                    new Alert(42L, "REPORT_DISPATCH_UNKNOWN", true, Instant.parse("2026-11-26T00:01:00Z")));
        });
    }

    @AfterEach
    void stop() {
        receiver.stop(0);
    }

    @ParameterizedTest
    @CsvSource({"'',wait=true", "?thread_id=234&%77ait=false&wait=false&wait=false,thread_id=234&wait=true",
            "?thread_id=234&label=a%26b,thread_id=234&label=a%26b&wait=true"})
    void sendsContentWithOneWaitParameterAndAcknowledgesTheCreatedMessage(String suffix, String expectedQuery) throws Exception {
        respond(200, "{\"id\":\"1234567890123456789\",\"content\":\"created\"}");
        new ReportAlertDispatcher(alerts, json, url + "/team" + suffix).deliver();
        var body = json.readTree(payload.get());
        assertThat(body.size()).isOne();
        assertThat(body.path("content").asText()).contains("2건", "2026-11-26T00:00:00Z",
                "#42 최초 · OPENAI_HTTP_503", "#42 재시도 · REPORT_DISPATCH_UNKNOWN (요청 전달 여부 미확정)");
        assertThat(query.get()).isEqualTo(expectedQuery);
        verify(alerts).acknowledge(lease.get());
        verify(alerts, never()).defer(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not JSON", "null", "{}", "{\"id\":\"\"}", "{\"id\":\"bad\"}",
            "{\"id\":123}", "{\"id\":\"18446744073709551616\"}", "{\"id\":\"123\"} trailing"})
    void unconfirmedMessageLeavesTheBatchForAnotherAttempt(String body) {
        respond(200, body);
        new ReportAlertDispatcher(alerts, json, url + "/team").deliver();
        verify(alerts).defer(lease.get());
        verify(alerts, never()).acknowledge(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {204, 429, 503})
    void noContentOrAnHttpErrorLeavesTheBatchForAnotherAttempt(int status) {
        respond(status, "");
        new ReportAlertDispatcher(alerts, json, url + "/team").deliver();
        verify(alerts).defer(lease.get());
        verify(alerts, never()).acknowledge(any());
    }

    private void respond(int status, String body) {
        receiver.createContext("/team", exchange -> {
            try (exchange) {
                query.set(exchange.getRequestURI().getRawQuery());
                payload.set(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
                byte[] bytes = body.getBytes(UTF_8);
                exchange.sendResponseHeaders(status, bytes.length > 0 ? bytes.length : -1);
                if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            }
        });
        receiver.start();
    }
}
