package com.react.feed.dto;

import java.time.Instant;
import java.util.Map;

/**
 * 강연방 이해도 집계. 전체 인원은 누적 고유 청중, 응답 인원은 최신 이해 상태가 있는 청중 수이며
 * 상태 비율의 분모는 전체 참여 인원이다. 과거 변경 이력은 합산하지 않는다.
 */
public record UnderstandingSummaryView(
        long roomId,
        long totalParticipants,
        long respondedParticipants,
        long unrespondedParticipants,
        double responseRate,
        Map<UnderstandingStatus, Long> counts,
        Map<UnderstandingStatus, Double> percentagesOfAllParticipants,
        Instant calculatedAt
) { }
