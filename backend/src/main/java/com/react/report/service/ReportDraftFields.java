package com.react.report.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.Arrays;

import static com.react.report.service.ReportGenerationException.invalidFormat;

/** 두 보고서 영역이 같은 기준으로 모델의 JSON 필드와 자료형을 확인한다. */
final class ReportDraftFields {
    private ReportDraftFields() {}

    static void requireFields(JsonNode object, String... fields) {
        if (object == null || !object.isObject() || object.size() != fields.length
                || !Arrays.stream(fields).allMatch(object::has)) throw invalidFormat();
    }

    static ArrayNode array(JsonNode object, String key) {
        if (object.get(key) instanceof ArrayNode result) return result;
        throw invalidFormat();
    }

    static String string(JsonNode value) {
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw invalidFormat();
        return value.textValue();
    }

    static long positiveId(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) {
            throw invalidFormat();
        }
        return value.longValue();
    }
}
