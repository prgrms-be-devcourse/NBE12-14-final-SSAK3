package com.react.chat.dto;

import java.util.List;

/**
 * 채팅 브로드캐스트 이벤트. 짧은 간격 동안 저장된 메시지를 한 프레임에 묶어 보낸다.
 * 다른 STOMP 이벤트처럼 { "type": ..., "data": {...} } 형식이다.
 */
public record ChatBatchEvent(String type, Data data) {
    public static final String TYPE = "CHAT_BATCH";

    public record Data(long roomId, List<Message> messages) { }

    /**
     * receivedAt 은 명세대로 ISO-8601 UTC 문자열이다. 메시지 변환기가 Instant 를 숫자로 내보내지 않게 문자열로 담는다.
     * attempt 는 발신자가 보낸 X-Chat-Attempt 값이며, 발신자 화면이 자신의 전송을 확인하는 데 쓴다.
     */
    public record Message(long messageId, long roomId, ChatSenderView sender, String content, String receivedAt,
                          String attempt) { }

    public static ChatBatchEvent of(long roomId, List<Message> messages) {
        return new ChatBatchEvent(TYPE, new Data(roomId, messages));
    }

    public static Message message(long roomId, ChatMessageView message, String attempt) {
        return new Message(message.messageId(), roomId, message.sender(), message.content(),
                message.receivedAt().toString(), attempt);
    }
}
