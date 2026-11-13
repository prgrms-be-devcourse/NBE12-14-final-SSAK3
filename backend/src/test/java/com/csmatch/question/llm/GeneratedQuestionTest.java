package com.csmatch.question.llm;

import com.csmatch.question.Enum.Difficulty;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedQuestionTest {

    private static final List<String> STEPS = List.of("조건 A를 적용한다", "그 결과에 조건 B를 적용한다");

    @Test
    void 보기_4개_정답_1개이면_통과한다() {
        assertThat(question("문제", List.of(choice("a", true), choice("b", false), choice("c", false), choice("d", false)),
                "a가 맞는 이유").validate(Difficulty.EASY)).isEmpty();
    }

    @Test
    void 보기가_4개가_아니면_거절한다() {
        assertThat(question("문제", List.of(choice("a", true), choice("b", false), choice("c", false)), "해설")
                .validate(Difficulty.EASY)).hasValueSatisfying(reason -> assertThat(reason).contains("4개"));
    }

    @Test
    void 정답이_0개나_2개이면_거절한다() {
        assertThat(question("문제", List.of(choice("a", false), choice("b", false), choice("c", false), choice("d", false)),
                "해설").validate(Difficulty.EASY)).isPresent();
        assertThat(question("문제", List.of(choice("a", true), choice("b", true), choice("c", false), choice("d", false)),
                "해설").validate(Difficulty.EASY)).isPresent();
    }

    @Test
    void 본문_해설_보기가_비어_있으면_거절한다() {
        List<GeneratedQuestion.Choice> ok = fourChoices();
        assertThat(question(" ", ok, "해설").validate(Difficulty.EASY)).isPresent();
        assertThat(question("문제", ok, "").validate(Difficulty.EASY)).isPresent();
        assertThat(question("문제", List.of(choice("a", true), choice(" ", false), choice("c", false), choice("d", false)),
                "해설").validate(Difficulty.EASY)).isPresent();
    }

    @Test
    void 같은_보기가_있으면_거절한다() {
        assertThat(question("문제", List.of(choice("a", true), choice("a", false), choice("c", false), choice("d", false)),
                "해설").validate(Difficulty.EASY)).hasValueSatisfying(reason -> assertThat(reason).contains("같은 보기"));
    }

    @Test
    void 보기가_500자를_넘으면_거절한다() {
        assertThat(question("문제", List.of(choice("a".repeat(501), true), choice("b", false), choice("c", false), choice("d", false)),
                "해설").validate(Difficulty.EASY)).isPresent();
    }

    @Test
    void 모두_정답_해당_없음_같은_보기는_거절한다() {
        assertThat(question("문제", List.of(choice("a", true), choice("b", false), choice("c", false), choice("모두 정답이다", false)),
                "해설").validate(Difficulty.EASY)).isPresent();
        assertThat(question("문제", List.of(choice("a", true), choice("b", false), choice("c", false), choice("해당 없음", false)),
                "해설").validate(Difficulty.EASY)).isPresent();
    }

    @Test
    void 해설이_보기_번호를_말하면_거절한다() {
        List<GeneratedQuestion.Choice> ok = fourChoices();
        assertThat(question("문제", ok, "①이 정답이다").validate(Difficulty.EASY)).isPresent();
        assertThat(question("문제", ok, "3번 보기는 틀리다").validate(Difficulty.EASY)).isPresent();
        assertThat(question("문제", ok, "정답은 2번이다").validate(Difficulty.EASY)).isPresent();
    }

    @Test
    void 해설에_번호가_들어가도_보기를_가리키지_않으면_통과한다() {
        assertThat(question("문제", fourChoices(), "TCP는 3-way handshake로 연결하고 4번의 FIN/ACK로 종료한다")
                .validate(Difficulty.EASY)).isEmpty();
    }

    @Test
    void 보기가_null이어도_예외_없이_거절한다() {
        assertThat(new GeneratedQuestion(STEPS, "문제", null, "해설").validate(Difficulty.EASY)).isPresent();
    }

    @Test
    void 정답이_오답_평균의_1점3배를_넘게_길면_거절한다() {
        // 정답 40자, 오답 평균 20자 → 2배
        assertThat(question("문제", List.of(choice("가".repeat(40), true), choice("나".repeat(20), false),
                choice("다".repeat(20), false), choice("라".repeat(20), false)), "해설").validate(Difficulty.MEDIUM))
                .hasValueSatisfying(reason -> assertThat(reason).contains("눈에 띄게 깁니다"));
    }

    @Test
    void 정답_길이가_오답과_비슷하면_통과한다() {
        // 정답 25자, 오답 평균 20자 → 1.25배
        assertThat(question("문제", List.of(choice("가".repeat(25), true), choice("나".repeat(20), false),
                choice("다".repeat(20), false), choice("라".repeat(20), false)), "해설").validate(Difficulty.MEDIUM)).isEmpty();
    }

    @Test
    void 정답이_가장_짧거나_보기가_짧으면_길이_검사를_하지_않는다() {
        assertThat(question("문제", List.of(choice("가".repeat(10), true), choice("나".repeat(40), false),
                choice("다".repeat(40), false), choice("라".repeat(40), false)), "해설").validate(Difficulty.MEDIUM)).isEmpty();
        // 정답이 20자 미만이면 비율이 커도 길이가 힌트가 되기 어렵다
        assertThat(question("문제", List.of(choice("TCP/IP 4계층", true), choice("UDP", false),
                choice("ICMP", false), choice("ARP", false)), "해설").validate(Difficulty.EASY)).isEmpty();
    }

    @Test
    void 어려움_문제는_풀이_단계가_2개_미만이면_거절한다() {
        assertThat(new GeneratedQuestion(List.of("정의를 떠올린다"), "문제", fourChoices(), "해설")
                .validate(Difficulty.HARD)).hasValueSatisfying(reason -> assertThat(reason).contains("풀이 단계"));
        assertThat(new GeneratedQuestion(List.of(" ", "정의를 떠올린다"), "문제", fourChoices(), "해설")
                .validate(Difficulty.HARD)).isPresent();
        assertThat(new GeneratedQuestion(null, "문제", fourChoices(), "해설").validate(Difficulty.HARD)).isPresent();
    }

    @Test
    void 어려움_문제도_풀이_단계가_2개_이상이면_통과한다() {
        assertThat(new GeneratedQuestion(STEPS, "문제", fourChoices(), "해설").validate(Difficulty.HARD)).isEmpty();
    }

    @Test
    void 쉬움과_보통은_풀이_단계가_1개여도_통과한다() {
        GeneratedQuestion oneStep = new GeneratedQuestion(List.of("정의를 떠올린다"), "문제", fourChoices(), "해설");
        assertThat(oneStep.validate(Difficulty.EASY)).isEmpty();
        assertThat(oneStep.validate(Difficulty.MEDIUM)).isEmpty();
        assertThat(new GeneratedQuestion(null, "문제", fourChoices(), "해설").validate(Difficulty.EASY)).isEmpty();
    }

    private static List<GeneratedQuestion.Choice> fourChoices() {
        return List.of(choice("a", true), choice("b", false), choice("c", false), choice("d", false));
    }

    private static GeneratedQuestion question(String content, List<GeneratedQuestion.Choice> choices, String explanation) {
        return new GeneratedQuestion(STEPS, content, choices, explanation);
    }

    private static GeneratedQuestion.Choice choice(String content, boolean correct) {
        return new GeneratedQuestion.Choice(content, correct);
    }
}
