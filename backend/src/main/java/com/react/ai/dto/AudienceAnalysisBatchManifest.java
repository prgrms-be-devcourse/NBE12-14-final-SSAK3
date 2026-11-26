package com.react.ai.dto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 원문을 복제하지 않고 배치 입력의 대표·원본 ID와 내용 지문을 고정한다. */
public record AudienceAnalysisBatchManifest(List<Message> messages) {
    public AudienceAnalysisBatchManifest {
        messages = List.copyOf(messages);
    }

    public static AudienceAnalysisBatchManifest from(AudienceAnalysisBatch batch) {
        return new AudienceAnalysisBatchManifest(batch.inputMessages().stream()
                .map(message -> new Message(message.messageId(), message.occurrenceCount(),
                        message.content().length(), sha256(message.content()),
                        batch.originalMessageIdsByRepresentative().get(message.messageId())))
                .toList());
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    public record Message(long representativeMessageId, int occurrenceCount, int contentLength,
                          String contentSha256, List<Long> originalMessageIds) {
        public Message {
            originalMessageIds = List.copyOf(originalMessageIds);
        }
    }
}
