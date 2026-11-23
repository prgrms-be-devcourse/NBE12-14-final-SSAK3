package com.react.poll.dto;

import com.react.poll.entity.UnderstandingStatus;

/**
 * 선택지 하나의 결과. percentageOfResponses 의 분모는 응답자 수, percentageOfAllParticipants 의 분모는 전체 참여자 수다.
 * 비율은 선택지마다 소수 첫째 자리로 반올림하므로 합이 100 에서 조금 어긋날 수 있다.
 */
public record PollOptionResultView(
        long optionId,
        String content,
        UnderstandingStatus understandingStatus,
        long count,
        double percentageOfResponses,
        double percentageOfAllParticipants
) { }
