package com.react.report.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** 전체 요약, 후속 답변 후보와 두 영역의 근거가 되는 원문·집계를 함께 보관한다. */
public record ReportContent(JsonNode overview, ReportFollowUp followUp, List<ReportTimeline.Window> timeline,
                            List<String> dataLimitations, String evidencePolicy) {}
