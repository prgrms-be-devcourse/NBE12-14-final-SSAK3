package com.react.report.dto;

import java.util.List;

/** 근거의 원문과 시각은 같은 보고서의 타임라인에서 읽는다. */
public record ReportFollowUp(Status status, List<Group> groups) {
    public enum Status { CANDIDATES, NO_QUESTIONS, ALL_ANSWERED, UNAVAILABLE }
    public enum Kind { DRAFT, PREPARATION }

    public record Group(String title, List<Question> questions, Kind kind, String text, List<Long> segmentIds) {}
    public record Question(List<Request> requests) {}
    public record Request(long messageId, String quote, boolean reexplanation) {}

    public static ReportFollowUp empty(Status status) {
        return new ReportFollowUp(status, List.of());
    }
}
