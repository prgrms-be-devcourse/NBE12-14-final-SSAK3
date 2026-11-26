package com.react.report.dto;

public record ReportStatusView(ReportReceipt.Status status, boolean retryAvailable, boolean delayed,
                               FailureCategory failureCategory) {
    public enum FailureCategory { AI_SERVICE, AI_OUTPUT, REPORT }
}
