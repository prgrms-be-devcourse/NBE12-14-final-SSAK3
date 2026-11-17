package com.react.ai.dto;

import java.time.LocalDateTime;

public record AudienceMessage(long messageId, long roomId, String content, LocalDateTime receivedAt) {
}
