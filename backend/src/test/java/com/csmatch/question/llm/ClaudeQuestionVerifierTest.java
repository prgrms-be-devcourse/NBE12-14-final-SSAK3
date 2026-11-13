package com.csmatch.question.llm;

import com.csmatch.question.llm.ClaudeQuestionVerifier.BlindAnswer;
import com.csmatch.question.llm.ClaudeQuestionVerifier.ExplanationCheck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClaudeQuestionVerifierTest {

    private static final String MODEL = "claude-opus-5-5";

    ClaudeApi api;
    ClaudeQuestionVerifier verifier;

    @BeforeEach
    void setUp() {
        api = mock(ClaudeApi.class);
        verifier = new ClaudeQuestionVerifier(api, MODEL);
    }

    @Test
    void 풀이와_해설이_모두_맞으면_통과한다() {
        solves(new BlindAnswer(List.of("1번 맞다"), 1, ""));
        explains(new ExplanationCheck(true, ""));

        VerificationResult result = verifier.verify(request(1));

        assertThat(result.passed()).isTrue();
        assertThat(result.reason()).isNull();
        verify(api, times(2)).call(eq(MODEL), any(String.class), any(String.class), any(Class.class), any(String.class));
    }

    @Test
    void 정답이_몇_번째_보기여도_번호로_비교한다() {
        solves(new BlindAnswer(List.of(), 4, ""));
        explains(new ExplanationCheck(true, ""));

        assertThat(verifier.verify(request(4)).passed()).isTrue();
    }

    @Test
    void 검증_모델이_정답을_정할_수_없다고_하면_실패하고_해설은_확인하지_않는다() {
        solves(new BlindAnswer(List.of(), 0, "2번과 3번이 모두 맞다"));

        VerificationResult result = verifier.verify(request(1));

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("정할 수 없다").contains("2번과 3번이 모두 맞다");
        verify(api, never()).call(any(String.class), any(String.class), any(String.class),
                eq(ExplanationCheck.class), any(String.class));
    }

    @Test
    void 고른_답이_저장된_정답과_다르면_실패하고_해설은_확인하지_않는다() {
        solves(new BlindAnswer(List.of(), 1, "개념 설명과 어긋난다"));

        VerificationResult result = verifier.verify(request(3));

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("1번").contains("3번").contains("개념 설명과 어긋난다");
        verify(api, never()).call(any(String.class), any(String.class), any(String.class),
                eq(ExplanationCheck.class), any(String.class));
    }

    @Test
    void 해설이_정답과_일치하지_않으면_실패한다() {
        solves(new BlindAnswer(List.of(), 2, ""));
        explains(new ExplanationCheck(false, "해설이 3번을 정답처럼 설명한다"));

        VerificationResult result = verifier.verify(request(2));

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("해설").contains("3번을 정답처럼 설명한다");
    }

    @Test
    void 이유가_비어_있거나_null이면_이유_없음으로_적는다() {
        solves(new BlindAnswer(List.of(), 2, null));

        assertThat(verifier.verify(request(1)).reason()).contains("이유 없음");
    }

    @Test
    void 호출이_실패하면_예외를_그대로_던진다() {
        when(api.call(any(String.class), any(String.class), any(String.class), eq(BlindAnswer.class), any(String.class)))
                .thenThrow(new LlmException("LLM 호출에 실패했습니다: IOException"));

        assertThatThrownBy(() -> verifier.verify(request(1)))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).isFatal()).isFalse());
    }

    @Test
    void 검증은_설정한_모델로_호출한다() {
        solves(new BlindAnswer(List.of(), 1, ""));
        explains(new ExplanationCheck(true, ""));

        verifier.verify(request(1));

        assertThat(verifier.model()).isEqualTo(MODEL);
        verify(api, times(2)).call(eq(MODEL), any(String.class), any(String.class), any(Class.class), any(String.class));
    }

    @Test
    void 모델_설정이_비어_있으면_기본_모델을_쓴다() {
        assertThat(new ClaudeQuestionVerifier(api, " ").model()).isEqualTo("claude-opus-5-5");
        assertThat(new ClaudeQuestionVerifier(api, null).model()).isEqualTo("claude-opus-5-5");
    }

    private void solves(BlindAnswer answer) {
        when(api.call(eq(MODEL), any(String.class), any(String.class), eq(BlindAnswer.class), any(String.class)))
                .thenReturn(answer);
    }

    private void explains(ExplanationCheck check) {
        when(api.call(eq(MODEL), any(String.class), any(String.class), eq(ExplanationCheck.class), any(String.class)))
                .thenReturn(check);
    }

    /** correctNumber 번째(1~4) 보기가 정답인 검증 요청. */
    private static VerificationRequest request(int correctNumber) {
        List<GeneratedQuestion.Choice> choices = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            choices.add(new GeneratedQuestion.Choice("보기 " + i, i == correctNumber));
        }
        GeneratedQuestion question = new GeneratedQuestion(List.of("풀이"), "문제 본문", choices, "해설 본문");
        return new VerificationRequest("TCP", "TCP 는 연결 지향 프로토콜이다.", question);
    }
}
