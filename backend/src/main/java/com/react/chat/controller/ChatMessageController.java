package com.react.chat.controller;

import com.react.chat.dto.ChatMessagePageView;
import com.react.chat.service.ChatMessageService;
import com.react.global.response.ApiResponse;
import com.react.global.websocket.StompActor;
import com.react.global.websocket.StompActorResolver;
import com.react.room.service.ParticipantSessionResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/messages")
@RequiredArgsConstructor
public class ChatMessageController {
    private final ChatMessageService messages;
    private final StompActorResolver actors;

    @GetMapping
    public ResponseEntity<ApiResponse<ChatMessagePageView>> getMessages(@PathVariable long roomId,
            @RequestParam(required = false) Long cursor, @RequestParam(defaultValue = "30") int size,
            @RequestHeader(name = StompActorResolver.CHAT_ROLE_HEADER, required = false) String role,
            Principal principal, HttpServletRequest request) {
        StompActor actor = actors.resolveHistory(roomId, principal, ParticipantSessionResolver.token(request, roomId), role);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(messages.getMessages(roomId, actor, cursor, size)));
    }
}
