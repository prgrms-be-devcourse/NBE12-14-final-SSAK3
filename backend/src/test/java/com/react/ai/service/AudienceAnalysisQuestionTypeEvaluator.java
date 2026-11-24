package com.react.ai.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Stored Gemini topics against manually labelled question messages. Test-only quality helper. */
final class AudienceAnalysisQuestionTypeEvaluator {
    private static final Set<String> QUESTION_COMPATIBLE_CATEGORIES = Set.of("QUESTION", "MIXED");

    QuestionEvaluation evaluate(List<Long> questionMessageIds, List<ActualTopic> actualTopics) {
        if (questionMessageIds == null || questionMessageIds.isEmpty()) {
            return QuestionEvaluation.notApplicableResult();
        }

        Set<Long> captured = new HashSet<>();
        Set<Long> correctlyCategorized = new HashSet<>();
        for (ActualTopic topic : actualTopics) {
            for (Long sourceMessageId : topic.sourceMessageIds()) {
                if (!questionMessageIds.contains(sourceMessageId)) {
                    continue;
                }
                captured.add(sourceMessageId);
                if (QUESTION_COMPATIBLE_CATEGORIES.contains(topic.category())) {
                    correctlyCategorized.add(sourceMessageId);
                }
            }
        }

        return new QuestionEvaluation(questionMessageIds.size(), captured.size(), correctlyCategorized.size(),
                questionMessageIds.stream().filter(id -> !captured.contains(id)).toList(), false);
    }

    record ActualTopic(String category, List<Long> sourceMessageIds) {
        ActualTopic {
            sourceMessageIds = List.copyOf(sourceMessageIds);
        }
    }

    record QuestionEvaluation(int labelledQuestionCount, int capturedQuestionCount, int correctlyCategorizedQuestionCount,
                              List<Long> missingQuestionMessageIds, boolean notApplicable) {
        static QuestionEvaluation notApplicableResult() {
            return new QuestionEvaluation(0, 0, 0, List.of(), true);
        }

        double captureRecall() {
            return notApplicable ? Double.NaN : (double) capturedQuestionCount / labelledQuestionCount;
        }

        double categoryAccuracyAmongLabelledQuestions() {
            return notApplicable ? Double.NaN : (double) correctlyCategorizedQuestionCount / labelledQuestionCount;
        }
    }
}
