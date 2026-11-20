package com.react.poll.dto;

/** 청중 투표 응답 요청. 참여자는 본문이 아니라 방별 참여자 쿠키로 서버가 확인한다. */
public record RespondPollRequest(Long optionId) { }
