package com.react.chat.dto;

import java.time.Instant;

public record ChatMessageView(
        long messageId,
        ChatSenderView sender,
        String content,
        Instant receivedAt
) { }
