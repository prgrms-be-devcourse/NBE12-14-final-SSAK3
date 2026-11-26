package com.react.report.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.report.repository.ReportAlertRepository;
import com.react.report.repository.ReportAlertRepository.Alert;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
public final class ReportAlertDispatcher {
    private final ReportAlertRepository alerts;
    private final ObjectMapper json;
    private final URI endpoint;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public ReportAlertDispatcher(ReportAlertRepository alerts, ObjectMapper json, String url) {
        this.alerts = alerts;
        this.json = json;
        var webhook = URI.create(url.trim());
        var host = webhook.getHost();
        boolean local = "127.0.0.1".equals(host) || "localhost".equals(host) || "[::1]".equals(host);
        if (host == null || webhook.getUserInfo() != null || webhook.getFragment() != null
                || !("https".equalsIgnoreCase(webhook.getScheme()) || local && "http".equalsIgnoreCase(webhook.getScheme()))) {
            throw new IllegalArgumentException("Invalid report alert endpoint");
        }
        var endpointBuilder = UriComponentsBuilder.fromUri(webhook);
        for (String name : List.copyOf(endpointBuilder.build(true).getQueryParams().keySet())) {
            if ("wait".equals(UriUtils.decode(name, StandardCharsets.UTF_8))) endpointBuilder.replaceQueryParam(name);
        }
        endpoint = endpointBuilder.queryParam("wait", "true").build(true).toUri();
    }

    public void deliver() {
        var lease = UUID.randomUUID();
        List<Alert> batch = alerts.claim(lease);
        if (batch.isEmpty()) return;
        try {
            String payload = json.writeValueAsString(Map.of("content", message(batch)));
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status >= 200 && status < 300 && confirmsMessage(response.body())) {
                alerts.acknowledge(lease);
            } else {
                log.warn("Discord 보고서 알림 전송을 확인하지 못했습니다. httpStatus={}, count={}", status, batch.size());
                alerts.defer(lease);
            }
        } catch (IOException error) {
            log.warn("보고서 알림 전달 실패. type={}, count={}", error.getClass().getSimpleName(), batch.size());
            alerts.defer(lease);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            // An interrupted worker leaves the batch for the next lease holder.
        }
    }

    private boolean confirmsMessage(byte[] body) throws IOException {
        var message = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(body);
        if (message == null) return false;
        var id = message.path("id").textValue();
        if (id == null || !id.matches("[1-9][0-9]{0,19}")) return false;
        try {
            Long.parseUnsignedLong(id);
            return true;
        } catch (NumberFormatException overflow) {
            return false;
        }
    }

    private static String message(List<Alert> batch) {
        var earliest = batch.stream().map(Alert::failedAt).min(java.time.Instant::compareTo).orElseThrow();
        var latest = batch.stream().map(Alert::failedAt).max(java.time.Instant::compareTo).orElseThrow();
        var message = new StringBuilder("RE:ACT 회고 보고서 AI 생성 알림 ").append(batch.size())
                .append("건\n발생 시각(UTC) ").append(earliest);
        if (!earliest.equals(latest)) message.append(" ~ ").append(latest);
        for (Alert failure : batch) {
            message.append("\n보고서 #").append(failure.reportId()).append(failure.retried() ? " 재시도 · " : " 최초 · ")
                    .append(failure.diagnosticCode());
            if ("REPORT_DISPATCH_UNKNOWN".equals(failure.diagnosticCode())) {
                message.append(" (요청 전달 여부 미확정)");
            }
        }
        return message.toString();
    }
}
