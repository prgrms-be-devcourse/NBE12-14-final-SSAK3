package com.react.poll.dto;

import com.react.poll.entity.UnderstandingStatus;

import java.time.Instant;
import java.util.Map;

/**
 * 마감된 이해도 체크 한 라운드의 결과. lackCount 는 헷갈림과 모르겠음의 합이고 lackRatio 의 분모는 응답자 수다.
 * lowUnderstanding 은 그 시점에 관찰한 수치일 뿐 원인을 단정하지 않는다. 청중 개개인의 선택은 담지 않는다.
 */
public record UnderstandingRoundView(
        long pollId,
        Instant openedAt,
        Instant closedAt,
        long totalParticipants,
        long respondedParticipants,
        Map<UnderstandingStatus, Long> counts,
        long lackCount,
        double lackRatio,
        boolean lowUnderstanding
) { }
