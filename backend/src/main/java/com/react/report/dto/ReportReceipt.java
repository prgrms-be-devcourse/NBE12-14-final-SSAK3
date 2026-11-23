package com.react.report.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record ReportReceipt(long reportId, long roomId, Status status, Instant createdAt) {
    public enum Status {
        PENDING, PROCESSING, COMPLETED, FAILED
    }

    public boolean inProgress() {
        return status == Status.PENDING || status == Status.PROCESSING;
    }

    @JsonProperty
    public String message() {
        return switch (status) {
            case PENDING -> "회고 보고서 생성 대기 중입니다.";
            case PROCESSING -> "회고 보고서를 생성하고 있습니다.";
            case COMPLETED -> "회고 보고서가 생성되었습니다.";
            case FAILED -> "보고서 생성에 실패했습니다.";
        };
    }
}
