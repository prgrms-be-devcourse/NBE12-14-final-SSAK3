package com.react.chat.controller;

import com.react.chat.dto.ChatMessageView;
import com.react.chat.dto.ChatSendRequest;
import com.react.chat.service.ChatMessageService;
import com.react.global.websocket.StompActor;
import com.react.global.websocket.StompActorResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
@Profile("!prod") // WebSocketConfig 와 같은 조건. 임시: 운영 환경 노출 차단
@RequiredArgsConstructor
public class ChatStompController {
    private final StompActorResolver actors;
    private final ChatMessageService messages;

    /** 작성자는 서버가 쿠키·로그인으로 정한다. 본문에서는 content 만 읽는다. */
    @MessageMapping("/rooms/{roomId}/chat")
    public void send(@DestinationVariable long roomId, @Payload ChatSendRequest request,
            Principal principal, SimpMessageHeaderAccessor headers) {
        StompActor actor = actors.resolve(roomId, principal, headers.getSessionAttributes());
        ChatMessageView saved = actor.type() == StompActor.Type.HOST
                ? messages.saveFromHost(roomId, actor.id(), request.content())
                : messages.saveFromParticipant(roomId, actor.id(), request.content());
    }
}
