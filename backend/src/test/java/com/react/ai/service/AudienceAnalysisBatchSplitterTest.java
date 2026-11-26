package com.react.ai.service;

import com.react.ai.dto.AudienceAnalysisInputMessage;
import com.react.ai.dto.AudienceMessage;
import com.react.ai.dto.PreprocessedAudienceMessages;
import com.react.ai.exception.GeminiAudienceAnalysisErrorCode;
import com.react.ai.exception.GeminiAudienceAnalysisException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudienceAnalysisBatchSplitterTest {
    private final AudienceAnalysisBatchSplitter splitter = new AudienceAnalysisBatchSplitter();

    @Test
    void 대표_메시지_100개는_한_배치이고_101개는_순서대로_두_배치다() {
        var oneHundred = splitter.split(input(100, "질문"), 100, 32_000);
        assertThat(oneHundred).hasSize(1);
        assertThat(oneHundred.getFirst().inputMessages()).hasSize(100);

        var oneHundredOne = splitter.split(input(101, "질문"), 100, 32_000);
        assertThat(oneHundredOne).hasSize(2);
        assertThat(oneHundredOne.getFirst().batchIndex()).isZero();
        assertThat(oneHundredOne.getLast().batchIndex()).isEqualTo(1);
        assertThat(oneHundredOne.getFirst().inputMessages()).hasSize(100);
        assertThat(oneHundredOne.getLast().inputMessages()).hasSize(1);
        assertThat(oneHundredOne.stream().flatMap(batch -> batch.inputMessages().stream())
                .map(AudienceAnalysisInputMessage::messageId)).containsExactlyElementsOf(
                        input(101, "질문").inputMessages().stream().map(AudienceAnalysisInputMessage::messageId).toList());
    }

    @Test
    void 본문_정확히_32000자는_한_배치이고_32001자는_둘로_나눈다() {
        var exact = inputWithContents("가".repeat(32_000));
        assertThat(splitter.split(exact, 100, 32_000)).hasSize(1);

        var over = inputWithContents("가".repeat(32_000), "나");
        var batches = splitter.split(over, 100, 32_000);
        assertThat(batches).hasSize(2);
        assertThat(batches.getFirst().inputCharacterCount()).isEqualTo(32_000);
        assertThat(batches.getLast().inputCharacterCount()).isEqualTo(1);
    }

    @Test
    void 단일_대표_메시지가_상한을_넘으면_명시적으로_실패한다() {
        assertThatThrownBy(() -> splitter.split(inputWithContents("가".repeat(32_001)), 100, 32_000))
                .isInstanceOfSatisfying(GeminiAudienceAnalysisException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(GeminiAudienceAnalysisErrorCode.INPUT_LIMIT_EXCEEDED));
    }

    @Test
    void 전처리된_동일_문장의_발생_횟수와_원본_ID_순서를_보존한다() {
        var preprocessed = new AudienceMessagePreprocessor().preprocess(List.of(
                new AudienceMessage(11, 1, " Redis? ", null),
                new AudienceMessage(12, 1, "redis?", null),
                new AudienceMessage(13, 1, "다른 질문?", null)));
        var batches = splitter.split(preprocessed, 1, 32_000);
        assertThat(batches).hasSize(2);
        assertThat(batches.getFirst().inputMessages().getFirst().occurrenceCount()).isEqualTo(2);
        assertThat(batches.getFirst().originalMessageIdsByRepresentative().get(11L)).containsExactly(11L, 12L);
        assertThat(batches.getLast().originalMessageIdsByRepresentative().get(13L)).containsExactly(13L);
    }

    @Test
    void 배치_사이에_대표_ID와_원본_ID가_중복되거나_누락되지_않는다() {
        var input = input(251, "질문");
        var batches = splitter.split(input, 100, 32_000);
        assertThat(batches).hasSize(3);
        assertThat(batches.stream().flatMap(batch -> batch.inputMessages().stream())
                .map(AudienceAnalysisInputMessage::messageId)).containsExactlyElementsOf(
                        input.inputMessages().stream().map(AudienceAnalysisInputMessage::messageId).toList());
        assertThat(batches.stream().flatMap(batch -> batch.inputMessages().stream()
                .flatMap(message -> batch.originalMessageIdsByRepresentative().get(message.messageId()).stream())))
                .containsExactlyElementsOf(
                        input.inputMessages().stream().map(AudienceAnalysisInputMessage::messageId).toList());
    }

    private PreprocessedAudienceMessages input(int count, String content) {
        List<AudienceAnalysisInputMessage> messages = new ArrayList<>();
        Map<Long, List<Long>> originals = new LinkedHashMap<>();
        for (long id = 1; id <= count; id++) {
            messages.add(new AudienceAnalysisInputMessage(id, content + id, 1));
            originals.put(id, List.of(id));
        }
        return new PreprocessedAudienceMessages(messages, originals);
    }

    private PreprocessedAudienceMessages inputWithContents(String... contents) {
        List<AudienceAnalysisInputMessage> messages = new ArrayList<>();
        Map<Long, List<Long>> originals = new LinkedHashMap<>();
        for (int i = 0; i < contents.length; i++) {
            long id = i + 1L;
            messages.add(new AudienceAnalysisInputMessage(id, contents[i], 1));
            originals.put(id, List.of(id));
        }
        return new PreprocessedAudienceMessages(messages, originals);
    }
}
