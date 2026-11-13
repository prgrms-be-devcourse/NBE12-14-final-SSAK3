package com.csmatch.question.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedQuestionTest {

    @Test
    void 보기_4개_정답_1개이면_통과한다() {
        assertThat(question("문제", List.of(choice("a", true), choice("b", false), choice("c", false), choice("d", false)),
                "a가 맞는 이유").validate()).isEmpty();
    }

    @Test
    void 보기가_4개가_아니면_거절한다() {
        assertThat(question("문제", List.of(choice("a", true), choice("b", false), choice("c", false)), "해설").validate())
                .hasValueSatisfying(reason -> assertThat(reason).contains("4개"));
    }

    @Test
    void 정답이_0개나_2개이면_거절한다() {
        assertThat(question("문제", List.of(choice("a", false), choice("b", false), choice("c", false), choice("d", false)),
                "해설").validate()).isPresent();
        assertThat(question("문제", List.of(choice("a", true), choice("b", true), choice("c", false), choice("d", false)),
                "해설").validate()).isPresent();
    }

    @Test
    void 본문_해설_보기가_비어_있으면_거절한다() {
        List<GeneratedQuestion.Choice> ok = List.of(choice("a", true), choice("b", false), choice("c", false), choice("d", false));
        assertThat(question(" ", ok, "해설").validate()).isPresent();
        assertThat(question("문제", ok, "").validate()).isPresent();
        assertThat(question("문제", List.of(choice("a", true), choice(" ", false), choice("c", false), choice("d", false)),
                "해설").validate()).isPresent();
    }

    @Test
    void 같은_보기가_있으면_거절한다() {
        assertThat(question("문제", List.of(choice("a", true), choice("a", false), choice("c", false), choice("d", false)),
                "해설").validate()).hasValueSatisfying(reason -> assertThat(reason).contains("같은 보기"));
    }

    @Test
    void 보기가_500자를_넘으면_거절한다() {
        assertThat(question("문제", List.of(choice("a".repeat(501), true), choice("b", false), choice("c", false), choice("d", false)),
                "해설").validate()).isPresent();
    }

    @Test
    void 모두_정답_해당_없음_같은_보기는_거절한다() {
        assertThat(question("문제", List.of(choice("a", true), choice("b", false), choice("c", false), choice("모두 정답이다", false)),
                "해설").validate()).isPresent();
        assertThat(question("문제", List.of(choice("a", true), choice("b", false), choice("c", false), choice("해당 없음", false)),
                "해설").validate()).isPresent();
    }

    @Test
    void 해설이_보기_번호를_말하면_거절한다() {
        List<GeneratedQuestion.Choice> ok = List.of(choice("a", true), choice("b", false), choice("c", false), choice("d", false));
        assertThat(question("문제", ok, "①이 정답이다").validate()).isPresent();
        assertThat(question("문제", ok, "3번 보기는 틀리다").validate()).isPresent();
        assertThat(question("문제", ok, "정답은 2번이다").validate()).isPresent();
    }

    @Test
    void 해설에_번호가_들어가도_보기를_가리키지_않으면_통과한다() {
        List<GeneratedQuestion.Choice> ok = List.of(choice("a", true), choice("b", false), choice("c", false), choice("d", false));
        assertThat(question("문제", ok, "TCP는 3-way handshake로 연결하고 4번의 FIN/ACK로 종료한다").validate()).isEmpty();
    }

    @Test
    void 보기가_null이어도_예외_없이_거절한다() {
        assertThat(new GeneratedQuestion("문제", null, "해설").validate()).isPresent();
    }

    private static GeneratedQuestion question(String content, List<GeneratedQuestion.Choice> choices, String explanation) {
        return new GeneratedQuestion(content, choices, explanation);
    }

    private static GeneratedQuestion.Choice choice(String content, boolean correct) {
        return new GeneratedQuestion.Choice(content, correct);
    }
}
