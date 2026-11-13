package com.csmatch.question.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionVerificationPromptTest {

    private static final VerificationRequest REQUEST = new VerificationRequest("TCP", "TCP 는 연결 지향 프로토콜이다.",
            new GeneratedQuestion(List.of("풀이"), " 문제 본문 ", List.of(
                    new GeneratedQuestion.Choice("첫째 보기", false),
                    new GeneratedQuestion.Choice("둘째 보기", true),
                    new GeneratedQuestion.Choice("셋째 보기", false),
                    new GeneratedQuestion.Choice("넷째 보기", false)), "해설 본문"));

    @Test
    void 풀이용_메시지에는_정답과_해설이_없다() {
        String prompt = QuestionVerificationPrompt.solveUser(REQUEST);

        assertThat(prompt).doesNotContain("해설 본문").doesNotContain("## 정답").doesNotContain("## 해설");
        assertThat(prompt).doesNotContain("풀이");
    }

    @Test
    void 풀이용_메시지에는_개념과_문제와_번호를_붙인_보기가_있다() {
        String prompt = QuestionVerificationPrompt.solveUser(REQUEST);

        assertThat(prompt).contains("TCP", "TCP 는 연결 지향 프로토콜이다.", "문제 본문");
        assertThat(prompt).contains("1. 첫째 보기", "2. 둘째 보기", "3. 셋째 보기", "4. 넷째 보기");
    }

    @Test
    void 해설_확인용_메시지에는_정답_번호와_해설이_있다() {
        String prompt = QuestionVerificationPrompt.explainUser(REQUEST, 2);

        assertThat(prompt).contains("## 정답\n2번").contains("## 해설\n해설 본문");
        assertThat(prompt).contains("2. 둘째 보기");
    }

    @Test
    void 문제_본문과_보기의_앞뒤_공백은_뺀다() {
        String prompt = QuestionVerificationPrompt.solveUser(REQUEST);

        assertThat(prompt).contains("## 문제\n문제 본문\n");
    }

    @Test
    void 시스템_프롬프트는_고정값이라_요청_내용이_섞이지_않는다() {
        assertThat(QuestionVerificationPrompt.SOLVE_SYSTEM).doesNotContain("TCP").doesNotContain("첫째 보기");
        assertThat(QuestionVerificationPrompt.EXPLAIN_SYSTEM).doesNotContain("TCP").doesNotContain("해설 본문");
    }
}
