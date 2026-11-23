package com.react.report.service;

import com.fasterxml.jackson.databind.JsonNode;

@FunctionalInterface
public interface ReportOverviewClient {
    JsonNode generate(JsonNode input);
}
