package com.react.report.service;

/** 생성 작업의 내부 실패 사유. 공급자 응답과 강연 원문을 예외에 담지 않는다. */
public final class ReportGenerationException extends RuntimeException {
    public enum Reason { LLM_REQUEST_FAILED, LLM_TEMPORARY_FAILURE, INVALID_REPORT_FORMAT, INVALID_REPORT_EVIDENCE }

    enum ProviderFailure {
        OPENAI_API_KEY_MISSING, OPENAI_MODEL_MISSING, OPENAI_HTTP_429_QUOTA, OPENAI_HTTP_429_UNCLASSIFIED,
        OPENAI_HTTP_429_RATE_LIMIT(Reason.LLM_TEMPORARY_FAILURE), OPENAI_TIMEOUT(Reason.LLM_TEMPORARY_FAILURE),
        OPENAI_NETWORK(Reason.LLM_TEMPORARY_FAILURE), OPENAI_TRANSPORT, OPENAI_REFUSAL, OPENAI_INCOMPLETE_RESPONSE;

        private final Reason reason;

        ProviderFailure() {
            this(Reason.LLM_REQUEST_FAILED);
        }

        ProviderFailure(Reason reason) {
            this.reason = reason;
        }
    }

    private final Reason reason;
    private final String diagnosticCode;

    private ReportGenerationException(Reason reason) {
        this(reason, reason.name());
    }

    private ReportGenerationException(Reason reason, String diagnosticCode) {
        super(reason.name());
        this.reason = reason;
        this.diagnosticCode = diagnosticCode;
    }

    public Reason reason() {
        return reason;
    }

    public String diagnosticCode() {
        return diagnosticCode;
    }

    static ReportGenerationException providerFailure(ProviderFailure failure) {
        return new ReportGenerationException(failure.reason, failure.name());
    }

    static ReportGenerationException httpFailure(int status) {
        Reason reason = status >= 500 && status < 600 ? Reason.LLM_TEMPORARY_FAILURE : Reason.LLM_REQUEST_FAILED;
        return new ReportGenerationException(reason, "OPENAI_HTTP_" + status);
    }

    public static ReportGenerationException requestFailed() {
        return new ReportGenerationException(Reason.LLM_REQUEST_FAILED);
    }

    public static ReportGenerationException temporaryFailure() {
        return new ReportGenerationException(Reason.LLM_TEMPORARY_FAILURE);
    }

    public static ReportGenerationException invalidFormat() {
        return new ReportGenerationException(Reason.INVALID_REPORT_FORMAT);
    }

    public static ReportGenerationException invalidEvidence() {
        return new ReportGenerationException(Reason.INVALID_REPORT_EVIDENCE);
    }
}
