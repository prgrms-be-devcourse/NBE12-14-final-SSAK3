package com.react.ai.service;

import com.react.ai.dto.AiAnalysisResult;
import com.react.ai.dto.AiTopicResult;
import com.react.ai.dto.AudienceAnalysisInputMessage;
import com.react.ai.dto.AudienceAnalysisLlmResponse;
import com.react.ai.exception.GeminiAudienceAnalysisException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/** Deterministic metric helpers for the opt-in Gemini filter comparison. */
final class AudienceAnalysisFilterComparisonSupport {
    private AudienceAnalysisFilterComparisonSupport() {
    }

    static boolean shouldCallGemini(List<AudienceAnalysisInputMessage> messages) {
        return messages != null && !messages.isEmpty();
    }

    static Invocation invoke(AudienceAnalysisLlmClient client, List<AudienceAnalysisInputMessage> messages) {
        return invoke(client, messages, System::nanoTime);
    }

    static Invocation invoke(AudienceAnalysisLlmClient client, List<AudienceAnalysisInputMessage> messages,
                             LongSupplier nanoClock) {
        if (!shouldCallGemini(messages)) {
            return new Invocation(false, null, 0L, null);
        }
        long startedAt = nanoClock.getAsLong();
        try {
            return new Invocation(true, client.analyze(messages), elapsedMillis(startedAt, nanoClock.getAsLong()), null);
        } catch (RuntimeException exception) {
            return new Invocation(true, null, elapsedMillis(startedAt, nanoClock.getAsLong()), exception);
        }
    }

    static AiAnalysisResult expandSourceMessageIds(AiAnalysisResult result,
                                                    Map<Long, List<Long>> originalIdsByRepresentative,
                                                    Collection<Long> allOriginalMessageIds) {
        Set<Long> allowedRepresentatives = originalIdsByRepresentative.keySet();
        Set<Long> assignedRepresentatives = new HashSet<>();
        Set<Long> expandedIds = new HashSet<>();
        List<AiTopicResult> expandedTopics = new ArrayList<>();

        for (AiTopicResult topic : result.topics()) {
            List<Long> originalIds = new ArrayList<>();
            for (Long representativeId : topic.sourceMessageIds()) {
                if (!allowedRepresentatives.contains(representativeId)) {
                    SourceIdError error = allOriginalMessageIds.contains(representativeId)
                            ? SourceIdError.FILTERED_OR_NON_REPRESENTATIVE_ID
                            : SourceIdError.UNKNOWN_MESSAGE_ID;
                    throw new SourceMessageValidationException(error);
                }
                if (!assignedRepresentatives.add(representativeId)) {
                    throw new SourceMessageValidationException(SourceIdError.DUPLICATE_MESSAGE_ID);
                }
                List<Long> mappedIds = originalIdsByRepresentative.get(representativeId);
                if (mappedIds == null || mappedIds.isEmpty()) {
                    throw new SourceMessageValidationException(SourceIdError.INVALID_REPRESENTATIVE_MAPPING);
                }
                for (Long originalId : mappedIds) {
                    if (!expandedIds.add(originalId)) {
                        throw new SourceMessageValidationException(SourceIdError.DUPLICATE_MESSAGE_ID);
                    }
                    originalIds.add(originalId);
                }
            }
            expandedTopics.add(new AiTopicResult(topic.topic(), topic.summary(), topic.category(), List.copyOf(originalIds)));
        }
        return new AiAnalysisResult(List.copyOf(expandedTopics));
    }

    static String sourceIdFailureStatus(SourceMessageValidationException exception) {
        return "SOURCE_ID_" + exception.error().name();
    }

    /**
     * Returns {@code null} when the fixture has no question labels. A missing ground-truth
     * set is not evidence of perfect question recall.
     */
    static Double questionRecall(List<Long> questionMessageIds,
                                 List<AudienceAnalysisClusteringEvaluator.ActualTopic> actualTopics) {
        if (questionMessageIds == null || questionMessageIds.isEmpty()) {
            return null;
        }
        Set<Long> covered = actualTopics.stream()
                .filter(topic -> topic.category().equals("QUESTION") || topic.category().equals("MIXED"))
                .flatMap(topic -> topic.sourceMessageIds().stream())
                .collect(Collectors.toSet());
        long found = questionMessageIds.stream().filter(covered::contains).count();
        return (double) found / questionMessageIds.size();
    }

    /** Nearest-rank percentile, rounded up; with three samples p95 is the maximum sample. */
    static long p95Millis(List<Long> samples) {
        if (samples == null || samples.isEmpty()) {
            return 0;
        }
        List<Long> sorted = samples.stream().sorted().toList();
        int rank = (int) Math.ceil(0.95 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }

    static Double averageMillis(List<Long> samples) {
        return samples == null || samples.isEmpty()
                ? null : samples.stream().mapToLong(Long::longValue).average().orElse(0.0);
    }

    /** Returns null if any attempted call has unknown usage/cost, avoiding misleading partial totals. */
    static Long sumKnown(List<Long> values) {
        return values == null || values.isEmpty() || values.stream().anyMatch(value -> value == null)
                ? null : values.stream().mapToLong(Long::longValue).sum();
    }

    private static long elapsedMillis(long startedAt, long endedAt) {
        return Math.max(0L, (endedAt - startedAt) / 1_000_000L);
    }

    record Invocation(boolean attempted, AudienceAnalysisLlmResponse response, long elapsedMillis,
                      RuntimeException failure) {
    }

    enum SourceIdError {
        UNKNOWN_MESSAGE_ID,
        FILTERED_OR_NON_REPRESENTATIVE_ID,
        DUPLICATE_MESSAGE_ID,
        INVALID_REPRESENTATIVE_MAPPING
    }

    static final class SourceMessageValidationException extends IllegalArgumentException {
        private final SourceIdError error;

        SourceMessageValidationException(SourceIdError error) {
            super(error.name());
            this.error = error;
        }

        SourceIdError error() {
            return error;
        }
    }
}
