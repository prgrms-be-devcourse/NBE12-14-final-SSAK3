package com.react.report.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record ReportDetailView(long reportId, long roomId, ReportReceipt.Status status,
                               Instant createdAt, Instant generatedAt, JsonNode content,
                               @JsonIgnore ReportStatusView.FailureCategory failureCategory) {
    @JsonProperty
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String message() {
        return failureCategory == null ? null : failureCategory.message();
    }
}
