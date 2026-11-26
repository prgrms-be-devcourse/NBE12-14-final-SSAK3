package com.react.ai.service;

import com.react.ai.dto.AudienceAnalysisBatch;
import com.react.ai.dto.AudienceAnalysisInputMessage;
import com.react.ai.dto.PreprocessedAudienceMessages;
import com.react.ai.exception.GeminiAudienceAnalysisErrorCode;
import com.react.ai.exception.GeminiAudienceAnalysisException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 현재 Gemini 클라이언트와 같은 String.length() 본문 길이 기준으로 순서대로 분할한다. */
@Component
public class AudienceAnalysisBatchSplitter {
    public List<AudienceAnalysisBatch> split(PreprocessedAudienceMessages input, int maxMessages,
                                             int maxInputCharacters) {
        if (input == null || maxMessages <= 0 || maxInputCharacters <= 0) {
            throw new IllegalArgumentException("AI 배치 분할 설정이 올바르지 않습니다.");
        }
        List<AudienceAnalysisBatch> batches = new ArrayList<>();
        List<AudienceAnalysisInputMessage> current = new ArrayList<>();
        Map<Long, List<Long>> originals = new LinkedHashMap<>();
        Set<Long> allRepresentatives = new HashSet<>();
        Set<Long> allOriginals = new HashSet<>();
        int characters = 0;

        for (AudienceAnalysisInputMessage message : input.inputMessages()) {
            if (message.messageId() <= 0 || message.content() == null || message.content().isBlank()
                    || message.occurrenceCount() <= 0 || !allRepresentatives.add(message.messageId())) {
                throw new IllegalArgumentException("AI 대표 메시지가 올바르지 않습니다.");
            }
            List<Long> ids = input.originalMessageIds(message.messageId());
            if (ids.isEmpty() || ids.size() != message.occurrenceCount() || !ids.contains(message.messageId())) {
                throw new IllegalArgumentException("AI 대표 메시지의 원본 ID 목록이 올바르지 않습니다.");
            }
            for (long id : ids) {
                if (id <= 0 || !allOriginals.add(id)) {
                    throw new IllegalArgumentException("AI 원본 메시지 ID가 중복되거나 올바르지 않습니다.");
                }
            }
            int length = message.content().length();
            if (length > maxInputCharacters) {
                throw new GeminiAudienceAnalysisException(GeminiAudienceAnalysisErrorCode.INPUT_LIMIT_EXCEEDED,
                        false, null, "단일 AI 대표 메시지가 입력 문자 수 상한을 초과했습니다.");
            }
            if (!current.isEmpty() && (current.size() >= maxMessages
                    || characters + length > maxInputCharacters)) {
                batches.add(new AudienceAnalysisBatch(batches.size(), current, originals));
                current = new ArrayList<>();
                originals = new LinkedHashMap<>();
                characters = 0;
            }
            current.add(message);
            originals.put(message.messageId(), ids);
            characters += length;
        }
        if (!current.isEmpty()) {
            batches.add(new AudienceAnalysisBatch(batches.size(), current, originals));
        }
        if (allRepresentatives.size() != input.originalMessageIdsByRepresentative().size()) {
            throw new IllegalArgumentException("AI 대표 메시지와 원본 ID 목록이 일치하지 않습니다.");
        }
        return List.copyOf(batches);
    }
}
