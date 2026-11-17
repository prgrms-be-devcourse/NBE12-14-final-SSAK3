package com.react.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiAnalysisJsonParserTest {
    private final AiAnalysisJsonParser parser = new AiAnalysisJsonParser(new ObjectMapper());

    @Test
    void 정상_주제_결과를_파싱한다() {
        var result = parser.parse("""
                {"topics":[{"topic":"Redis 사용 이유","summary":"도입 이유를 궁금해했습니다.",
                "category":"QUESTION","sourceMessageIds":[1001,1002]}]}
                """);

        assertThat(result.topics()).hasSize(1);
        assertThat(result.topics().getFirst().sourceMessageIds()).containsExactly(1001L, 1002L);
    }

    @Test
    void 빈_주제_목록은_정상_결과다() {
        assertThat(parser.parse("{\"topics\":[]}").topics()).isEmpty();
    }

    @Test
    void 명세와_다른_필드나_잘못된_category는_거절한다() {
        assertThatThrownBy(() -> parser.parse("""
                {"topics":[{"topic":"주제","summary":"요약","category":"UNKNOWN",
                "sourceMessageIds":[1],"extra":true}]}
                """))
                .isInstanceOf(InvalidAiAnalysisResultException.class);
    }

    @Test
    void 중복되거나_비어있는_원문_ID는_거절한다() {
        assertThatThrownBy(() -> parser.parse("""
                {"topics":[{"topic":"주제","summary":"요약","category":"QUESTION",
                "sourceMessageIds":[1,1]}]}
                """))
                .isInstanceOf(InvalidAiAnalysisResultException.class);
        assertThatThrownBy(() -> parser.parse("""
                {"topics":[{"topic":"주제","summary":"요약","category":"QUESTION",
                "sourceMessageIds":[]}]}
                """))
                .isInstanceOf(InvalidAiAnalysisResultException.class);
    }

    @Test
    void 올바른_JSON이_아니면_거절한다() {
        assertThatThrownBy(() -> parser.parse("{topics:[]}"))
                .isInstanceOf(InvalidAiAnalysisResultException.class);
    }

    @Test
    void 소수_메시지_ID와_200자를_초과한_주제는_거절한다() {
        assertThatThrownBy(() -> parser.parse("""
                {"topics":[{"topic":"주제","summary":"요약","category":"QUESTION",
                "sourceMessageIds":[1001.5]}]}
                """))
                .isInstanceOf(InvalidAiAnalysisResultException.class);
        assertThatThrownBy(() -> parser.parse("""
                {"topics":[{"topic":"%s","summary":"요약","category":"QUESTION",
                "sourceMessageIds":[1]}]}
                """.formatted("가".repeat(201))))
                .isInstanceOf(InvalidAiAnalysisResultException.class);
    }
}
