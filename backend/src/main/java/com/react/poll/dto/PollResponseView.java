package com.react.poll.dto;

import java.time.Instant;

/** 청중 투표 응답·변경 결과. changed 는 이번 요청으로 저장된 선택이 달라졌는지다. */
public record PollResponseView(Long pollId, Long participantId, Long optionId, boolean changed,
                               Instant respondedAt, Instant closesAt) { }
