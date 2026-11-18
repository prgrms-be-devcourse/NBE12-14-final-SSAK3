package com.react.poll.entity;

/** HOST 는 강연자가 직접, TIMEOUT 은 제한 시간 도달, ROOM_ENDED 는 강연 종료로 마감된 경우다. */
public enum PollCloseReason {
    HOST, TIMEOUT, ROOM_ENDED
}
