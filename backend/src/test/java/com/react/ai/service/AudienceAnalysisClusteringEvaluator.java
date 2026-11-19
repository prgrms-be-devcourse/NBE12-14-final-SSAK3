package com.react.ai.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Gemini 수동 품질 테스트에서만 사용하는 메시지 쌍 기반 클러스터링 평가기다.
 * 주제명과 요약은 비교하지 않고, 같은 그룹에 속한 메시지 ID 쌍만 비교한다.
 */
final class AudienceAnalysisClusteringEvaluator {

    ClusterEvaluation evaluate(
            Collection<Long> knownMessageIds,
            List<? extends Collection<Long>> expectedGroups,
            List<? extends Collection<Long>> actualGroups
    ) {
        Set<Long> knownIds = Set.copyOf(knownMessageIds);
        Set<MessagePair> expectedPairs = pairs(expectedGroups, knownIds, "기대 그룹");
        Set<MessagePair> actualPairs = pairs(actualGroups, knownIds, "실제 그룹");

        Set<MessagePair> correctlyGroupedPairs = intersection(expectedPairs, actualPairs);
        Set<MessagePair> incorrectlyGroupedPairs = difference(actualPairs, expectedPairs);
        Set<MessagePair> splitPairs = difference(expectedPairs, actualPairs);

        double precision = ratio(correctlyGroupedPairs.size(), actualPairs.size());
        double recall = ratio(correctlyGroupedPairs.size(), expectedPairs.size());
        double f1 = precision + recall == 0.0 ? 0.0 : 2.0 * precision * recall / (precision + recall);

        return new ClusterEvaluation(
                expectedPairs,
                actualPairs,
                correctlyGroupedPairs,
                incorrectlyGroupedPairs,
                splitPairs,
                precision,
                recall,
                f1
        );
    }

    QualityEvaluation evaluateQuality(
            List<Long> mustCoverMessageIds,
            List<ExpectedCluster> expectedClusters,
            List<ActualTopic> actualTopics,
            List<String> mustNotClaims
    ) {
        Set<Long> actualSourceIds = new LinkedHashSet<>();
        for (ActualTopic actualTopic : actualTopics) {
            actualSourceIds.addAll(actualTopic.sourceMessageIds());
        }

        List<Long> missingMustCoverMessageIds = mustCoverMessageIds.stream()
                .filter(messageId -> !actualSourceIds.contains(messageId))
                .toList();

        List<CategoryViolation> categoryViolations = new ArrayList<>();
        for (int actualTopicIndex = 0; actualTopicIndex < actualTopics.size(); actualTopicIndex++) {
            ActualTopic actualTopic = actualTopics.get(actualTopicIndex);
            List<ExpectedCluster> matchedClusters = expectedClusters.stream()
                    .filter(cluster -> intersects(cluster.messageIds(), actualTopic.sourceMessageIds()))
                    .toList();
            if (matchedClusters.isEmpty()) {
                categoryViolations.add(CategoryViolation.unmatched(actualTopicIndex, actualTopic));
                continue;
            }
            for (ExpectedCluster cluster : matchedClusters) {
                if (!cluster.allowedCategories().contains(actualTopic.category())) {
                    categoryViolations.add(CategoryViolation.disallowed(
                            actualTopicIndex, actualTopic, cluster.clusterId(), cluster.allowedCategories()));
                }
            }
        }

        List<StanceReviewItem> stanceReviewItems = expectedClusters.stream()
                .flatMap(cluster -> cluster.requiredStances().stream()
                        .map(stance -> new StanceReviewItem(
                                cluster.clusterId(),
                                stance,
                                matchingTopicReferences(stance.messageIds(), actualTopics)
                        )))
                .toList();

        return new QualityEvaluation(
                List.copyOf(missingMustCoverMessageIds),
                List.copyOf(categoryViolations),
                List.copyOf(stanceReviewItems),
                List.copyOf(mustNotClaims)
        );
    }

    private boolean intersects(List<Long> first, List<Long> second) {
        Set<Long> firstIds = new HashSet<>(first);
        return second.stream().anyMatch(firstIds::contains);
    }

    private List<ActualTopicReference> matchingTopicReferences(List<Long> stanceMessageIds,
                                                                List<ActualTopic> actualTopics) {
        List<ActualTopicReference> references = new ArrayList<>();
        for (int index = 0; index < actualTopics.size(); index++) {
            ActualTopic actualTopic = actualTopics.get(index);
            if (intersects(stanceMessageIds, actualTopic.sourceMessageIds())) {
                references.add(new ActualTopicReference(
                        index, actualTopic.topic(), actualTopic.category(), actualTopic.summary(),
                        actualTopic.sourceMessageIds()));
            }
        }
        return List.copyOf(references);
    }

    private Set<MessagePair> pairs(
            List<? extends Collection<Long>> groups,
            Set<Long> knownIds,
            String groupType
    ) {
        Set<Long> assignedIds = new LinkedHashSet<>();
        Set<MessagePair> pairs = new LinkedHashSet<>();

        for (Collection<Long> group : groups) {
            if (group == null || group.isEmpty()) {
                continue;
            }

            List<Long> ids = new ArrayList<>(group);
            for (long id : ids) {
                if (!knownIds.contains(id)) {
                    throw new IllegalArgumentException(groupType + "에 입력 메시지에 없는 ID가 있습니다: " + id);
                }
                if (!assignedIds.add(id)) {
                    throw new IllegalArgumentException(groupType + "에 중복된 메시지 ID가 있습니다: " + id);
                }
            }

            for (int left = 0; left < ids.size(); left++) {
                for (int right = left + 1; right < ids.size(); right++) {
                    pairs.add(MessagePair.of(ids.get(left), ids.get(right)));
                }
            }
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(pairs));
    }

    private Set<MessagePair> intersection(Set<MessagePair> first, Set<MessagePair> second) {
        Set<MessagePair> result = new LinkedHashSet<>(first);
        result.retainAll(second);
        return Collections.unmodifiableSet(new LinkedHashSet<>(result));
    }

    private Set<MessagePair> difference(Set<MessagePair> first, Set<MessagePair> second) {
        Set<MessagePair> result = new LinkedHashSet<>(first);
        result.removeAll(second);
        return Collections.unmodifiableSet(new LinkedHashSet<>(result));
    }

    private double ratio(int numerator, int denominator) {
        if (denominator != 0) {
            return (double) numerator / denominator;
        }
        return 1.0;
    }

    record ClusterEvaluation(
            Set<MessagePair> expectedPairs,
            Set<MessagePair> actualPairs,
            Set<MessagePair> correctlyGroupedPairs,
            Set<MessagePair> incorrectlyGroupedPairs,
            Set<MessagePair> splitPairs,
            double precision,
            double recall,
            double f1
    ) {
    }

    record ExpectedCluster(String clusterId, List<Long> messageIds, Set<String> allowedCategories,
                           List<RequiredStance> requiredStances) {
        ExpectedCluster {
            messageIds = List.copyOf(messageIds);
            allowedCategories = Set.copyOf(allowedCategories);
            requiredStances = requiredStances == null ? List.of() : List.copyOf(requiredStances);
        }
    }

    record RequiredStance(List<Long> messageIds, String meaning) {
        RequiredStance {
            messageIds = List.copyOf(messageIds);
        }
    }

    record ActualTopic(String topic, String category, String summary, List<Long> sourceMessageIds) {
        ActualTopic {
            sourceMessageIds = List.copyOf(sourceMessageIds);
        }
    }

    record QualityEvaluation(
            List<Long> missingMustCoverMessageIds,
            List<CategoryViolation> categoryViolations,
            List<StanceReviewItem> stanceReviewItems,
            List<String> mustNotClaimsForHumanReview
    ) {
        boolean allMustCoverMessagesIncluded() {
            return missingMustCoverMessageIds.isEmpty();
        }

        boolean categoriesAllowed() {
            return categoryViolations.isEmpty();
        }
    }

    record CategoryViolation(int actualTopicIndex, String actualTopic, String actualCategory,
                             List<Long> sourceMessageIds, String expectedClusterId,
                             Set<String> allowedCategories, String reason) {
        static CategoryViolation unmatched(int actualTopicIndex, ActualTopic actualTopic) {
            return new CategoryViolation(actualTopicIndex, actualTopic.topic(), actualTopic.category(),
                    actualTopic.sourceMessageIds(), null, Set.of(), "기대 그룹과 연결되지 않은 결과");
        }

        static CategoryViolation disallowed(int actualTopicIndex, ActualTopic actualTopic,
                                            String expectedClusterId, Set<String> allowedCategories) {
            return new CategoryViolation(actualTopicIndex, actualTopic.topic(), actualTopic.category(),
                    actualTopic.sourceMessageIds(), expectedClusterId, Set.copyOf(allowedCategories),
                    "허용되지 않은 category");
        }
    }

    record StanceReviewItem(String expectedClusterId, RequiredStance requiredStance,
                            List<ActualTopicReference> actualTopicReferences) {
    }

    record ActualTopicReference(int actualTopicIndex, String topic, String category, String summary,
                                List<Long> sourceMessageIds) {
    }

    record MessagePair(long firstMessageId, long secondMessageId) {
        static MessagePair of(long firstMessageId, long secondMessageId) {
            if (firstMessageId == secondMessageId) {
                throw new IllegalArgumentException("동일한 메시지 ID로 쌍을 만들 수 없습니다: " + firstMessageId);
            }
            return firstMessageId < secondMessageId
                    ? new MessagePair(firstMessageId, secondMessageId)
                    : new MessagePair(secondMessageId, firstMessageId);
        }

        @Override
        public String toString() {
            return "(" + firstMessageId + ", " + secondMessageId + ")";
        }
    }
}
