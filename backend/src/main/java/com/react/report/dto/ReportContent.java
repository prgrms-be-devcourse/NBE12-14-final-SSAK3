package com.react.report.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** 검증한 전체 강연 요약과 서버가 조립한 원문·집계를 함께 보관할 보고서 본문. */
public record ReportContent(JsonNode overview, List<ReportTimeline.Window> timeline,
                            List<String> dataLimitations, String evidencePolicy) {}
