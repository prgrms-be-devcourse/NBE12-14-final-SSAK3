package com.react.poll.dto;

import java.time.Instant;

/** 재접속한 청중의 내 응답. 응답하지 않았으면 optionId·respondedAt 이 null 이며 어떤 선택지로도 간주하지 않는다. */
public record MyPollResponseView(Long pollId, Long participantId, Long optionId, Instant respondedAt) { }
