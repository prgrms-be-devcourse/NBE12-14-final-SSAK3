package com.react.poll.entity;

/** 이해도 체크의 고정 선택지. 응답하지 않은 청중은 어느 값으로도 간주하지 않는다. */
public enum UnderstandingStatus {
    UNDERSTOOD("이해됨"),
    CONFUSED("헷갈림"),
    NOT_UNDERSTOOD("모르겠음");

    private final String label;

    UnderstandingStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
