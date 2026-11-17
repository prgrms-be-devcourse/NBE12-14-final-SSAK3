package com.react.chat.dto;

import java.util.List;

public record ChatMessagePageView(
        List<ChatMessageView> messages,
        Long nextCursor,
        boolean hasNext
) {}
