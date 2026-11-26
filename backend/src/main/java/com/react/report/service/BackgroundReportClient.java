package com.react.report.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.regex.Pattern;

public interface BackgroundReportClient {
    JsonNode submit(JsonNode input, String attemptKey);
    JsonNode retrieve(String responseId);
    void delete(String responseId);
    JsonNode draft(JsonNode completedResponse);

    Pattern RESPONSE_ID = Pattern.compile("resp_[A-Za-z0-9_-]{1,250}");

    static boolean validResponseId(String value) {
        return value != null && RESPONSE_ID.matcher(value).matches();
    }

    /** 작성 요청은 전송됐을 수 있다. 같은 시도를 다시 제출하지 않는다. */
    final class SubmissionUnknown extends RuntimeException {
        private final String diagnosticCode;

        public SubmissionUnknown(ReportGenerationException cause) {
            super("OPENAI_SUBMISSION_UNKNOWN");
            this.diagnosticCode = cause.diagnosticCode();
        }

        public String diagnosticCode() {
            return diagnosticCode;
        }
    }

    final class NotFound extends RuntimeException {
        public NotFound() {
            super("OPENAI_RESPONSE_NOT_FOUND");
        }
    }
}
