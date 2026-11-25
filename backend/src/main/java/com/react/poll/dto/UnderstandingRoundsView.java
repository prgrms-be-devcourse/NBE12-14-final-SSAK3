package com.react.poll.dto;

import java.util.List;

/** 마감된 이해도 체크를 시간순으로 나열한 결과. ratio·minCount 는 이 응답의 이해 부족 판단에 쓴 기준이다. */
public record UnderstandingRoundsView(
        long roomId,
        double ratio,
        int minCount,
        List<UnderstandingRoundView> rounds
) { }
