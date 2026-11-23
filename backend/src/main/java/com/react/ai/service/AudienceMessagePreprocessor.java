package com.react.ai.service;

import com.react.ai.dto.AudienceAnalysisInputMessage;
import com.react.ai.dto.AudienceMessage;
import com.react.ai.dto.PreprocessedAudienceMessages;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 명백한 반응성 노이즈만 제외하고, 동일한 정규화 문자열만 대표 메시지로 병합한다. */
@Component
public class AudienceMessagePreprocessor {
    private static final String REACTION_JAMO = "ㅋㅎㅠㅜ";
    private static final Set<String> EXACT_NOISE_MESSAGES = Set.of(
            "ㄷㄷ", "ㄷㄷㄷ", "ㅎㅇ", "ㅊㅋ", "ㅂㅂ", "하이", "안녕하세요"
    );

    public PreprocessedAudienceMessages preprocess(List<AudienceMessage> messages) {
        Map<String, MessageGroup> groups = new LinkedHashMap<>();
        for (AudienceMessage message : messages) {
            String content = normalizeDisplayContent(message.content());
            if (isObviousNoise(content) || isExactNoiseMessage(content)) {
                continue;
            }
            String key = content.toLowerCase(Locale.ROOT);
            groups.computeIfAbsent(key, ignored -> new MessageGroup(message.messageId(), content))
                    .originalMessageIds.add(message.messageId());
        }

        List<AudienceAnalysisInputMessage> inputMessages = new ArrayList<>();
        Map<Long, List<Long>> originalIdsByRepresentative = new LinkedHashMap<>();
        for (MessageGroup group : groups.values()) {
            inputMessages.add(new AudienceAnalysisInputMessage(
                    group.representativeMessageId, group.content, group.originalMessageIds.size()));
            originalIdsByRepresentative.put(group.representativeMessageId, group.originalMessageIds);
        }
        return new PreprocessedAudienceMessages(inputMessages, originalIdsByRepresentative);
    }

    private String normalizeDisplayContent(String content) {
        if (content == null) {
            return "";
        }
        return Normalizer.normalize(content, Normalizer.Form.NFC)
                .strip()
                .replaceAll("\\s+", " ");
    }

    private boolean isObviousNoise(String content) {
        if (content.isEmpty()) {
            return true;
        }
        if (content.codePoints().noneMatch(Character::isLetterOrDigit)) {
            return true;
        }
        return isReactionJamoAndSymbolOnly(content);
    }

    /** 정규화된 전체 문자열이 작은 반응·인사 사전과 정확히 일치할 때만 제외한다. */
    private boolean isExactNoiseMessage(String content) {
        return EXACT_NOISE_MESSAGES.contains(content.toLowerCase(Locale.ROOT));
    }

    /**
     * 단일 반응 자모는 기존처럼 보존한다. 둘 이상의 반응 자모와 공백·기호·이모지만 섞인 경우만 노이즈다.
     */
    private boolean isReactionJamoAndSymbolOnly(String content) {
        int reactionJamoCount = 0;
        for (int codePoint : content.codePoints().toArray()) {
            if (REACTION_JAMO.indexOf(codePoint) >= 0) {
                reactionJamoCount++;
                continue;
            }
            if (!isWhitespaceOrSymbol(codePoint)) {
                return false;
            }
        }
        return reactionJamoCount >= 2;
    }

    private boolean isWhitespaceOrSymbol(int codePoint) {
        if (Character.isWhitespace(codePoint)) {
            return true;
        }
        return switch (Character.getType(codePoint)) {
            case Character.CONNECTOR_PUNCTUATION,
                 Character.DASH_PUNCTUATION,
                 Character.START_PUNCTUATION,
                 Character.END_PUNCTUATION,
                 Character.INITIAL_QUOTE_PUNCTUATION,
                 Character.FINAL_QUOTE_PUNCTUATION,
                 Character.OTHER_PUNCTUATION,
                 Character.MATH_SYMBOL,
                 Character.CURRENCY_SYMBOL,
                 Character.MODIFIER_SYMBOL,
                 Character.OTHER_SYMBOL,
                 Character.NON_SPACING_MARK,
                 Character.COMBINING_SPACING_MARK,
                 Character.ENCLOSING_MARK,
                 Character.FORMAT -> true;
            default -> false;
        };
    }

    private static final class MessageGroup {
        private final long representativeMessageId;
        private final String content;
        private final List<Long> originalMessageIds = new ArrayList<>();

        private MessageGroup(long representativeMessageId, String content) {
            this.representativeMessageId = representativeMessageId;
            this.content = content;
        }
    }
}
