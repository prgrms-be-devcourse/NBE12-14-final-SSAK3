package com.csmatch.question.llm;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Anthropic Claude API 로 문제를 검증. 정답을 숨기고 풀게 한 뒤 답이 같으면 해설을 확인.
 * 해설 확인은 풀이가 맞았을 때만 호출, 정답이 틀린 문제는 호출 한 번으로 끝남.
 */
@Component
public class ClaudeQuestionVerifier implements QuestionVerifier {

    private static final String DEFAULT_MODEL = "claude-opus-5-5";

    private final ClaudeApi api;
    private final String model;

    public ClaudeQuestionVerifier(ClaudeApi api, @Value("${app.llm.verify-model:" + DEFAULT_MODEL + "}") String model) {
        this.api = api;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
    }

    /** 정답과 해설을 보지 않은 채 문제를 푼 결과. 이 record 로 JSON 스키마를 만들어 응답 형식을 강제한다. */
    public record BlindAnswer(
            @JsonPropertyDescription("보기마다 한 줄. 개념 설명의 어떤 사실로 맞다 또는 틀리다고 판단했는지 쓴다.")
            List<String> reasoning,
            @JsonPropertyDescription("정답이라고 판단한 보기 번호 1~4. 정답을 하나로 정할 수 없으면 0.")
            int answer,
            @JsonPropertyDescription("정답을 하나로 정할 수 없을 때 그 이유. 결함이 없으면 빈 문자열.")
            String problem) {
    }

    /** 해설이 정답과 일치하는지 확인한 결과. */
    public record ExplanationCheck(
            @JsonPropertyDescription("해설이 정답을 올바르게 설명하고 정답이나 개념 설명과 어긋나지 않으면 true.")
            boolean consistent,
            @JsonPropertyDescription("consistent 가 false 일 때 어느 부분이 문제인지 한 문장. 결함이 없으면 빈 문자열.")
            String problem) {
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public VerificationResult verify(VerificationRequest request) {
        GeneratedQuestion question = request.question();
        int correctNumber = correctNumber(question);
        String logContext = "verify concept=" + request.conceptName();

        BlindAnswer solved = api.call(model, QuestionVerificationPrompt.SOLVE_SYSTEM,
                QuestionVerificationPrompt.solveUser(request), BlindAnswer.class, logContext + " step=solve");
        if (solved.answer() == 0) {
            return VerificationResult.fail("검증 모델이 정답을 하나로 정할 수 없다고 판단했습니다: "
                    + describe(solved.problem()));
        }
        if (solved.answer() != correctNumber) {
            return VerificationResult.fail("검증 모델이 고른 답(" + solved.answer() + "번)이 저장된 정답("
                    + correctNumber + "번)과 다릅니다: " + describe(solved.problem()));
        }

        ExplanationCheck checked = api.call(model, QuestionVerificationPrompt.EXPLAIN_SYSTEM,
                QuestionVerificationPrompt.explainUser(request, correctNumber), ExplanationCheck.class,
                logContext + " step=explanation");
        if (!checked.consistent()) {
            return VerificationResult.fail("해설이 정답과 일치하지 않습니다: " + describe(checked.problem()));
        }
        return VerificationResult.pass();
    }

    /** 정답 보기의 번호(1~4). 서버 검증을 통과한 문제만 받으므로 정답은 정확히 하나다. */
    private static int correctNumber(GeneratedQuestion question) {
        for (int i = 0; i < question.choices().size(); i++) {
            if (question.choices().get(i).correct()) {
                return i + 1;
            }
        }
        throw new IllegalArgumentException("정답 보기가 없습니다.");
    }

    private static String describe(String problem) {
        return problem == null || problem.isBlank() ? "이유 없음" : problem.trim();
    }
}
