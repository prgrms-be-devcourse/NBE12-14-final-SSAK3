package com.react.chat.dto;

/** 채팅 방송 이벤트. 다른 STOMP 이벤트처럼 { "type": ..., "data": {...} } 형식이다. */
public record ChatCreatedEvent(String type, Data data) {
    public static final String TYPE = "CHAT_CREATED";

    /** receivedAt 은 명세대로 ISO-8601 UTC 문자열이다. 메시지 변환기가 Instant 를 숫자로 내보내지 않게 문자열로 담는다. */
    public record Data(long messageId, long roomId, ChatSenderView sender, String content, String receivedAt) { }

    public static ChatCreatedEvent of(long roomId, ChatMessageView message) {
        return new ChatCreatedEvent(TYPE, new Data(message.messageId(), roomId, message.sender(),
                message.content(), message.receivedAt().toString()));
    }
}
