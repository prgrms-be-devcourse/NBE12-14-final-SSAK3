package com.react.chat.controller;

import com.react.chat.dto.ChatMessagePageView;
import com.react.chat.service.ChatMessageService;
import com.react.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/messages")
@RequiredArgsConstructor
public class ChatMessageController {
    private final ChatMessageService messages;

    // TODO(SCRUM-164): 해당 강연방의 강연자·참여자만 조회하도록 인증·권한 검증을 붙인다.
    @GetMapping
    public ApiResponse<ChatMessagePageView> getMessages(@PathVariable long roomId,
            @RequestParam(required = false) Long cursor, @RequestParam(defaultValue = "30") int size) {
        return ApiResponse.of(messages.getMessages(roomId, cursor, size));
    }
}
