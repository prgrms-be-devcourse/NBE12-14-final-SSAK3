package com.react.chat.controller;

import com.react.chat.dto.ChatCreatedEvent;
import com.react.chat.dto.ChatMessageView;
import com.react.chat.dto.ChatSendRequest;
import com.react.chat.service.ChatMessageService;
import com.react.global.websocket.StompActor;
import com.react.global.websocket.StompActorResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Slf4j
@Controller
@Profile("!prod") // WebSocketConfig 와 같은 조건. 임시: 운영 환경 노출 차단
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
}
