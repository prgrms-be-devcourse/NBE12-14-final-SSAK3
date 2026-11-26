package com.react.chat.controller;

import com.react.chat.dto.ChatBatchEvent;
import com.react.chat.dto.ChatErrorEvent;
import com.react.chat.dto.ChatMessageView;
import com.react.chat.dto.ChatSendRequest;
import com.react.chat.service.ChatBroadcastBatcher;
import com.react.chat.service.ChatMessageService;
import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.global.monitoring.ChatMessageMetrics;
import com.react.global.websocket.StompActor;
import com.react.global.websocket.StompActorResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.util.UUID;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatStompController {
    private static final String ATTEMPT_HEADER = "X-Chat-Attempt";
    private final StompActorResolver actors;
    private final ChatMessageService messages;
    private final ChatBroadcastBatcher batcher;
    private final ChatMessageMetrics metrics;

    /** 작성자는 서버가 쿠키·로그인으로 정한다. 본문에서는 content 만 읽는다. */
    @MessageMapping("/rooms/{roomId}/chat")
    public void send(@DestinationVariable long roomId, @Payload ChatSendRequest request,
                     SimpMessageHeaderAccessor headers) {
        StompActor actor = actors.resolveChat(roomId, headers);
        String attempt = attempt(headers);
        ChatMessageView saved = actor.type() == StompActor.Type.HOST
                ? messages.saveFromHost(roomId, actor.id(), request.content(), attempt)
                : messages.saveFromParticipant(roomId, actor.id(), request.content(), attempt);

        // SCRUM-91: 저장에 성공한 채팅 메시지 수를 기록한다.
        metrics.recordSavedMessage();

        // 저장 서비스가 끝났으므로 이미 커밋된 뒤다. 전송은 배처가 잠깐 모아서 한 프레임으로 보낸다.
        // 같은 전송 식별자의 재전송이면 이미 저장된 메시지가 같은 식별자로 다시 전송된다.
        batcher.enqueue(roomId, ChatBatchEvent.message(roomId, saved, attempt));
    }

    /** 거절 사유(LIVE 아님, 길이, 권한 등)를 보낸 사람의 개인 우편함으로만 알린다. */
    @MessageExceptionHandler(BusinessException.class)
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ChatErrorEvent handleBusiness(BusinessException e, SimpMessageHeaderAccessor headers) {
        return ChatErrorEvent.of(e.getErrorCode(), attempt(headers));
    }

    /** 본문이 { "content": ... } 형태가 아니면 변환 단계에서 실패한다. */
    @MessageExceptionHandler(MessageConversionException.class)
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ChatErrorEvent handleMalformed(MessageConversionException e, SimpMessageHeaderAccessor headers) {
        return ChatErrorEvent.of(ErrorCode.INVALID_INPUT, attempt(headers));
    }

    /** 예상하지 못한 예외는 내부 내용을 알리지 않고, 원인은 서버 로그에만 남긴다. */
    @MessageExceptionHandler(Exception.class)
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ChatErrorEvent handleUnexpected(Exception e, SimpMessageHeaderAccessor headers) {
        log.error("STOMP 채팅 처리 중 예상하지 못한 오류", e);
        return ChatErrorEvent.of(ErrorCode.INTERNAL_ERROR, attempt(headers));
    }

    private static String attempt(SimpMessageHeaderAccessor headers) {
        String value = headers.getFirstNativeHeader(ATTEMPT_HEADER);
        if (value == null || value.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value) ? value : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
