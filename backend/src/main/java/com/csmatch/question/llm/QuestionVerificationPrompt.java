package com.csmatch.question.llm;

import java.util.List;

/**
 * 문제 검증 프롬프트. 검증은 두 번의 호출로 나뉜다.
 * 1) 정답과 해설을 숨기고 문제를 스스로 풀게 한다. 정답을 알려 주면 맞다고 동의하는 쪽으로 기울기 때문이다.
 * 2) 1)의 답이 저장된 정답과 같을 때만, 해설이 그 정답과 일치하는지 확인한다.
 * 난이도 기준과 생성 규칙은 QuestionPrompt 에 있고, 검증은 난이도를 판정하지 않는다.
 */
final class QuestionVerificationPrompt {

    private QuestionVerificationPrompt() {
    }

    // 모든 요청에서 같은 값이어야 프롬프트 캐시가 적용된다. 요청마다 달라지는 값은 넣지 않는다.
    static final String SOLVE_SYSTEM = """
            당신은 컴퓨터공학(CS) 객관식 문제의 검증자입니다. 다른 모델이 만든 문제를 직접 풀어 보고, 문제에 결함이 있는지 판단합니다.
            사용자가 개념 설명, 문제, 보기 4개를 줍니다. 정답이 무엇인지는 알려 주지 않습니다.

            ## 푸는 방법
            - 개념 설명에 적힌 사실만을 근거로 풉니다. 설명에 없는 외부 지식으로 정답을 정하지 마세요.
            - 보기를 하나씩 개념 설명과 대조해 맞는지 틀린지 판단하고, reasoning 에 보기마다 한 줄로 적습니다.
            - answer 에는 정답이라고 판단한 보기 번호(1~4)를 적습니다.
            - 개념 설명만으로 정답을 하나로 정할 수 없으면 answer 를 0으로 하고 problem 에 이유를 적습니다. 다음 경우입니다.
              - 정답으로 볼 수 있는 보기가 둘 이상입니다.
              - 정답으로 볼 수 있는 보기가 없습니다.
              - 정답의 근거가 개념 설명에 없습니다.
              - 문제가 모호해서 해석에 따라 정답이 달라집니다.
            - 결함이 없으면 problem 은 빈 문자열로 둡니다.
            """;

    static final String EXPLAIN_SYSTEM = """
            당신은 컴퓨터공학(CS) 객관식 문제의 해설 검수자입니다.
            사용자가 개념 설명, 문제, 보기 4개, 정답 번호, 해설을 줍니다. 정답은 이미 확인되었으니 해설만 검사합니다.

            ## 검사 기준
            다음을 모두 만족하면 consistent 를 true 로 합니다.
            - 해설이 정답으로 지정된 보기가 맞는 이유를 설명합니다.
            - 해설이 다른 보기를 정답이라고 하거나 정답과 모순되는 말을 하지 않습니다.
            - 해설이 정답과 오답에 대해 말하는 내용이 개념 설명과 어긋나지 않습니다.
            하나라도 어기면 consistent 를 false 로 하고 problem 에 어느 부분이 문제인지 한 문장으로 적습니다.
            문장이 어색한 정도는 문제 삼지 않습니다. 결함이 없으면 problem 은 빈 문자열로 둡니다.
            """;

    /** 정답과 해설은 넣지 않는다. */
    static String solveUser(VerificationRequest request) {
        StringBuilder prompt = new StringBuilder();
        appendConcept(prompt, request);
        appendQuestion(prompt, request.question());
        return prompt.toString();
    }

    /** correctNumber 는 정답 보기의 번호(1~4)다. */
    static String explainUser(VerificationRequest request, int correctNumber) {
        StringBuilder prompt = new StringBuilder();
        appendConcept(prompt, request);
        appendQuestion(prompt, request.question());
        prompt.append("\n## 정답\n").append(correctNumber).append("번\n");
        prompt.append("\n## 해설\n").append(request.question().explanation().trim()).append('\n');
        return prompt.toString();
    }

    private static void appendConcept(StringBuilder prompt, VerificationRequest request) {
        prompt.append("## 개념\n").append(request.conceptName()).append("\n\n");
        prompt.append("## 개념 설명\n").append(request.conceptDescription()).append("\n\n");
    }

    private static void appendQuestion(StringBuilder prompt, GeneratedQuestion question) {
        prompt.append("## 문제\n").append(question.content().trim()).append("\n\n");
        prompt.append("## 보기\n");
        List<GeneratedQuestion.Choice> choices = question.choices();
        for (int i = 0; i < choices.size(); i++) {
            prompt.append(i + 1).append(". ").append(choices.get(i).content().trim()).append('\n');
        }
    }
}
