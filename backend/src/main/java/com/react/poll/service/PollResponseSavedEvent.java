package com.react.poll.service;

/** 투표 응답이 저장되거나 바뀌었다는 알림. 트랜잭션이 커밋된 뒤에만 결과 방송을 일으킨다. */
public record PollResponseSavedEvent(long roomId, long pollId) { }
