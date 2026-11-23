package com.react.poll.service;

import com.react.poll.dto.PollLifecycleEvent;

/** 투표가 생성되거나 마감됐다는 알림. 트랜잭션이 커밋된 뒤에만 청중·강연자에게 방송한다. */
public record PollLifecycleChangedEvent(long roomId, PollLifecycleEvent payload) { }
