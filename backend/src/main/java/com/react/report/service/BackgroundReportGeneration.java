package com.react.report.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.global.exception.BusinessException;
import com.react.report.dto.ReportInput;
import com.react.report.repository.ReportAttemptRepository;
import com.react.report.repository.ReportAttemptRepository.Attempt;
import com.react.report.repository.ReportGenerationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
public class BackgroundReportGeneration {
    private final ReportGenerationRepository reports;
    private final ReportAttemptRepository attempts;
    private final ReportOverviewService overview;
    private final BackgroundReportClient client;
    private final ObjectMapper json;
    private final boolean enabled;

    public BackgroundReportGeneration(ReportGenerationRepository reports, ReportAttemptRepository attempts,
                                      ReportOverviewService overview, BackgroundReportClient client,
                                      ObjectMapper json, @Value("${app.ai.report.background-enabled:false}") boolean enabled) {
        this.reports = reports;
        this.attempts = attempts;
        this.overview = overview;
        this.client = client;
        this.json = json;
        this.enabled = enabled;
    }

    boolean handles(long reportId) {
        return enabled || attempts.hasAttempt(reportId);
    }

    /** Called with the report's connection-owned dispatch lock; no DB transaction spans an HTTP call. */
    void start(long roomId, ReportGenerationRepository.Candidate report) {
        String input;
        JsonNode prompt;
        try {
            var snapshot = overview.prepare(roomId, report.email());
            if (!snapshot.catalog().hasAudience()) {
                reports.completePending(report.reportId(), json.writeValueAsString(overview.assemble(snapshot, null)));
                return;
            }
            input = json.writeValueAsString(snapshot.input());
            prompt = overview.prompt(snapshot);
        } catch (JsonProcessingException | BusinessException | IllegalArgumentException failure) {
            reports.failPending(report.reportId(), "REPORT_GENERATION_FAILED");
            return;
        }
        attempts.reserve(report.reportId(), input).ifPresent(attempt -> {
            JsonNode response;
            try {
                response = client.submit(prompt, attempt.attemptKey().toString());
            } catch (BackgroundReportClient.SubmissionUnknown uncertain) {
                attempts.unknown(attempt.attemptKey());
                log.warn("보고서 전송 결과 미확인. reportId={}, code={}", report.reportId(), uncertain.diagnosticCode());
                return;
            } catch (ReportGenerationException failure) {
                attempts.fail(attempt.attemptKey(), failure.reason().name(), failure.diagnosticCode());
                return;
            }
            if (!matchesAttempt(response, attempt)) {
                attempts.unknown(attempt.attemptKey());
                return;
            }
            // Bind the verified provider ID before processing output or making another network call.
            var saved = attempts.attach(attempt.attemptKey(), response.path("id").asText());
            // Even a partial admission response can provide a recoverable ID; poll its full resource.
            if (response.hasNonNull("status")) {
                saved.ifPresent(current -> consume(current, response));
            }
        });
    }

    /** A signed project webhook can concern other applications. Match the attempt before touching any report. */
    void receiveWebhook(String responseId) {
        JsonNode response = client.retrieve(responseId);
        String key = response.path("metadata").path("report_attempt").asText("");
        UUID attemptKey;
        try {
            attemptKey = UUID.fromString(key);
        } catch (IllegalArgumentException unrelatedResponse) {
            return;
        }
        if (!attemptKey.toString().equals(key)) return;
        attempts.attach(attemptKey, responseId).ifPresent(attempt -> consume(attempt, response));
    }

    void poll(String responseId) {
        var current = attempts.findByResponseId(responseId);
        if (current.isEmpty()) return;
        var attempt = current.get();
        try {
            JsonNode response = client.retrieve(responseId);
            if (matchesAttempt(response, attempt)) {
                consume(attempt, response);
            } else {
                attempts.unbindUnverifiedResponse(attempt.attemptKey(), responseId);
            }
        } catch (BackgroundReportClient.NotFound expired) {
            attempts.failRemote(attempt, "LLM_TEMPORARY_FAILURE", "OPENAI_RESPONSE_NOT_FOUND");
        }
    }

    private boolean matchesAttempt(JsonNode response, Attempt attempt) {
        return attempt.attemptKey().toString().equals(response.path("metadata").path("report_attempt").textValue());
    }

    private void consume(Attempt attempt, JsonNode response) {
        String status = response.path("status").asText();
        switch (status) {
            case "queued", "in_progress" -> { /* Retain the global slot until a terminal result. */ }
            case "completed" -> saveValidated(attempt, response);
            case "failed" -> {
                String code = response.path("error").path("code").asText("");
                boolean transientFailure = "server_error".equals(code) || "rate_limit_exceeded".equals(code);
                attempts.failRemote(attempt, transientFailure ? "LLM_TEMPORARY_FAILURE" : "LLM_REQUEST_FAILED",
                        transientFailure ? "OPENAI_BACKGROUND_TEMPORARY_FAILURE" : "OPENAI_BACKGROUND_FAILURE");
            }
            case "cancelled", "canceled", "incomplete" ->
                    attempts.failRemote(attempt, "LLM_REQUEST_FAILED", "OPENAI_INCOMPLETE_RESPONSE");
            default -> attempts.failRemote(attempt, "INVALID_REPORT_FORMAT", "OPENAI_INVALID_RESPONSE_STATUS");
        }
    }

    private void saveValidated(Attempt attempt, JsonNode response) {
        String content;
        try {
            // Room data can change after dispatch. Validate against the very input sent to the model.
            var snapshot = overview.snapshot(json.readValue(attempt.inputJson(), ReportInput.class));
            content = json.writeValueAsString(overview.assemble(snapshot, client.draft(response)));
        } catch (ReportGenerationException invalidDraft) {
            attempts.failRemote(attempt, invalidDraft.reason().name(), invalidDraft.diagnosticCode());
            return;
        } catch (JsonProcessingException invalidSnapshot) {
            attempts.failRemote(attempt, "REPORT_GENERATION_FAILED", null);
            return;
        }
        attempts.complete(attempt, content);
    }
}
