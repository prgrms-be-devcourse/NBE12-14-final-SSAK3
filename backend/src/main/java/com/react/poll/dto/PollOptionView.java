package com.react.poll.dto;

import com.react.poll.entity.UnderstandingStatus;

/** understandingStatus 는 이해도 체크의 선택지에만 있고 일반 투표에서는 null 이다. */
public record PollOptionView(long optionId, String content, int displayOrder, UnderstandingStatus understandingStatus) { }
