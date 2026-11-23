package com.react.report.service;

/** 생성 작업의 내부 실패 사유. 공급자 응답과 강연 원문을 예외에 담지 않는다. */
public final class ReportGenerationException extends RuntimeException {
    public enum Reason { LLM_REQUEST_FAILED, INVALID_REPORT_FORMAT, INVALID_REPORT_EVIDENCE }

    private final Reason reason;

    private ReportGenerationException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public static ReportGenerationException requestFailed() {
        return new ReportGenerationException(Reason.LLM_REQUEST_FAILED);
    }

    public static ReportGenerationException invalidFormat() {
        return new ReportGenerationException(Reason.INVALID_REPORT_FORMAT);
    }

    public static ReportGenerationException invalidEvidence() {
        return new ReportGenerationException(Reason.INVALID_REPORT_EVIDENCE);
    }
}
