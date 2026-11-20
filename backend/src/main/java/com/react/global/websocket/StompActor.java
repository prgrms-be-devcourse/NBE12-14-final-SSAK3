package com.react.global.websocket;

/** STOMP 요청자가 어느 방에서 누구인지. id 는 HOST 면 강연자 userId, PARTICIPANT 면 참여자 ID 다. */
public record StompActor(Type type, long id) {
    public enum Type { HOST, PARTICIPANT }

    public static StompActor host(long userId) {
        return new StompActor(Type.HOST, userId);
    }

    public static StompActor participant(long participantId) {
        return new StompActor(Type.PARTICIPANT, participantId);
    }
}
