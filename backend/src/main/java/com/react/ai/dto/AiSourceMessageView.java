package com.react.ai.dto;

import java.time.Instant;

public record AiSourceMessageView(long messageId, long participantId, String nickname, String content,
                                  Instant receivedAt) {
}
