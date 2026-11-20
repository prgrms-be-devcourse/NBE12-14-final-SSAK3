package com.react.chat.controller;

import com.react.chat.dto.ChatCreatedEvent;
import com.react.chat.dto.ChatErrorEvent;
import com.react.chat.dto.ChatMessageView;
import com.react.chat.dto.ChatSendRequest;
import com.react.chat.service.ChatMessageService;
import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatStompController {
    private final StompActorResolver actors;
    private final ChatMessageService messages;
    private final SimpMessagingTemplate broker;

    /** 작성자는 서버가 쿠키·로그인으로 정한다. 본문에서는 content 만 읽는다. */
    @MessageMapping("/rooms/{roomId}/chat")
    public void send(@DestinationVariable long roomId, @Payload ChatSendRequest request,
            Principal principal, SimpMessageHeaderAccessor headers) {
        StompActor actor = actors.resolve(roomId, principal, headers.getSessionAttributes());
        ChatMessageView saved = actor.type() == StompActor.Type.HOST
                ? messages.saveFromHost(roomId, actor.id(), request.content())
                : messages.saveFromParticipant(roomId, actor.id(), request.content());

        // 저장 서비스가 끝났으므로 이미 커밋된 뒤다. 방송이 실패해도 저장된 채팅은 유지하고, 내역 조회로 복구할 수 있다.
        try {
            broker.convertAndSend("/topic/rooms/" + roomId + "/chat", ChatCreatedEvent.of(roomId, saved));
        } catch (RuntimeException e) {
            log.warn("채팅 전송 실패. roomId={}, messageId={}", roomId, saved.messageId(), e);
        }
    }

    /** 거절 사유(LIVE 아님, 길이, 권한 등)를 보낸 사람의 개인 우편함으로만 알린다. */
    @MessageExceptionHandler(BusinessException.class)
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ChatErrorEvent handleBusiness(BusinessException e) {
        return ChatErrorEvent.of(e.getErrorCode());
    }

    /** 본문이 { "content": ... } 형태가 아니면 변환 단계에서 실패한다. */
    @MessageExceptionHandler(MessageConversionException.class)
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ChatErrorEvent handleMalformed(MessageConversionException e) {
        return ChatErrorEvent.of(ErrorCode.INVALID_INPUT);
    }

    /** 예상하지 못한 예외는 내부 내용을 알리지 않고, 원인은 서버 로그에만 남긴다. */
    @MessageExceptionHandler(Exception.class)
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ChatErrorEvent handleUnexpected(Exception e) {
        log.error("STOMP 채팅 처리 중 예상하지 못한 오류", e);
        return ChatErrorEvent.of(ErrorCode.INTERNAL_ERROR);
    }
}
