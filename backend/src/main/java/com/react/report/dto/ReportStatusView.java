package com.react.report.dto;

public record ReportStatusView(ReportReceipt.Status status, boolean retryAvailable, boolean delayed,
                               FailureCategory failureCategory) {
    public enum FailureCategory {
        AI_SERVICE, AI_OUTPUT, UNCONFIRMED, RECOVERY_EXPIRED, REPORT;

        public static FailureCategory from(ReportReceipt.Status status, String reason) {
            if (status != ReportReceipt.Status.FAILED) return null;
            return switch (reason) {
                case "REPORT_DISPATCH_UNKNOWN" -> UNCONFIRMED;
                case "REPORT_DISPATCH_EXPIRED" -> RECOVERY_EXPIRED;
                case "LLM_REQUEST_FAILED", "LLM_TEMPORARY_FAILURE" -> AI_SERVICE;
                case "INVALID_REPORT_FORMAT", "INVALID_REPORT_EVIDENCE" -> AI_OUTPUT;
                case null, default -> REPORT;
            };
        }

        public String message() {
            return switch (this) {
                case UNCONFIRMED -> "보고서 생성 요청의 처리 결과를 확인하고 있습니다.";
                case RECOVERY_EXPIRED -> "보고서 생성 요청의 결과를 확인할 수 있는 기간이 지났습니다.";
                default -> "보고서 생성에 실패했습니다.";
            };
        }
    }
}
