package com.react.ai.service;

import com.react.ai.domain.TopicCategory;
import com.react.ai.dto.AiAnalysisResult;
import com.react.ai.dto.AiTopicResult;
import com.react.ai.dto.AudienceMessage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AudienceMessagePreprocessorTest {
    private final AudienceMessagePreprocessor preprocessor = new AudienceMessagePreprocessor();
    private final LocalDateTime receivedAt = LocalDateTime.of(2026, 11, 23, 10, 0);

    @Test
    void 명백한_이모지_기호_반응만_제외하고_짧은_질문_요청_이해부족은_보존한다() {
        var result = preprocessor.preprocess(List.of(
                message(1L, "👍👍"),
                message(2L, "!!!"),
                message(3L, "ㅋㅋㅋ"),
                message(4L, "ㅋㅋㅎㅎ"),
                message(5L, "ㅋㅋㅋ ㅋㅋㅋ"),
                message(6L, "ㅋㅋㅋㅋ!!!"),
                message(7L, "ㅠㅠㅜㅜ"),
                message(8L, "👍👍!!!"),
                message(9L, "왜요? ㅋㅋ"),
                message(10L, "이해가 안 돼요ㅠㅠ"),
                message(11L, "Redis?"),
                message(12L, "예시요"),
                message(13L, "모르겠어요"),
                message(14L, "ㅋ")
        ));

        assertThat(result.inputMessages()).extracting(input -> input.messageId())
                .containsExactly(9L, 10L, 11L, 12L, 13L, 14L);
        assertThat(result.inputMessages()).extracting(input -> input.occurrenceCount())
                .containsOnly(1);
    }

    @Test
    void 정규화된_문자열이_노이즈_사전과_정확히_일치할때만_제외한다() {
        var result = preprocessor.preprocess(List.of(
                message(20L, "ㄷㄷ"),
                message(21L, "ㄷㄷㄷ"),
                message(22L, "ㅎㅇ"),
                message(23L, "ㅊㅋ"),
                message(24L, "ㅂㅂ"),
                message(25L, "  하이  "),
                message(26L, "안녕하세요"),
                message(27L, "ㅇ"),
                message(28L, "ㅇㅇ"),
                message(29L, "ㄱㄱ"),
                message(30L, "네"),
                message(31L, "아니요"),
                message(32L, "좋아요"),
                message(33L, "별로예요"),
                message(34L, "감사합니다"),
                message(35L, "왜요?"),
                message(36L, "다시요"),
                message(37L, "모르겠어요"),
                message(38L, "Redis?"),
                message(39L, "안녕하세요, 질문 있어요")
        ));

        assertThat(result.inputMessages()).extracting(input -> input.messageId())
                .containsExactly(27L, 28L, 29L, 30L, 31L, 32L, 33L, 34L, 35L, 36L, 37L, 38L, 39L);
        assertThat(result.inputMessages()).extracting(input -> input.content())
                .contains("안녕하세요, 질문 있어요");
    }

    @Test
    void NFC_공백_영문_대소문자만_정규화해_동일_메시지를_병합한다() {
        var result = preprocessor.preprocess(List.of(
                message(10L, "  Redis   API "),
                message(11L, "redis api"),
                message(12L, "Cafe\u0301"),
                message(13L, "Café")
        ));

        assertThat(result.inputMessages()).containsExactly(
                new com.react.ai.dto.AudienceAnalysisInputMessage(10L, "Redis API", 2),
                new com.react.ai.dto.AudienceAnalysisInputMessage(12L, "Café", 2)
        );
        assertThat(result.originalMessageIds(10L)).containsExactly(10L, 11L);
        assertThat(result.originalMessageIds(12L)).containsExactly(12L, 13L);
    }

    @Test
    void 의미가_다른_질문은_문자열이_완전히_같지_않으면_병합하지_않는다() {
        var result = preprocessor.preprocess(List.of(
                message(40L, "Redis를 써야 하나요?"),
                message(41L, "Redis를 안 써도 되나요?")
        ));

        assertThat(result.inputMessages()).extracting(input -> input.messageId())
                .containsExactly(40L, 41L);
    }

    @Test
    void LLM_대표_ID를_실제_원본_ID_전체로_확장한다() {
        var preprocessed = preprocessor.preprocess(List.of(
                message(50L, "Redis  도입 이유"),
                message(51L, "redis 도입 이유"),
                message(52L, "MySQL 대안")
        ));

        var expanded = preprocessed.expandSourceMessageIds(new AiAnalysisResult(List.of(
                new AiTopicResult("Redis", "도입 이유", TopicCategory.QUESTION, List.of(50L))
        )));

        assertThat(expanded.topics().getFirst().sourceMessageIds()).containsExactly(50L, 51L);
    }

    private AudienceMessage message(long id, String content) {
        return new AudienceMessage(id, 1L, content, receivedAt);
    }
}
