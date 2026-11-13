package com.csmatch.question.llm;

import com.csmatch.question.Enum.Difficulty;

/**
 * 문제 생성 프롬프트. 난이도 기준과 생성 규칙은 docs/game-rules.md 의 "문제 생성 규칙", "난이도 기준"과 같은 내용이다.
 * 한쪽을 고치면 다른 쪽도 함께 고친다.
 */
final class QuestionPrompt {

    private QuestionPrompt() {
    }

    // 모든 요청에서 같은 값이어야 프롬프트 캐시가 적용된다. 요청마다 달라지는 값은 넣지 않는다.
    static final String SYSTEM = """
            당신은 컴퓨터공학(CS) 객관식 퀴즈 출제자입니다. 두 사람이 같은 문제를 동시에 풀고 먼저 맞히는 사람이 득점하는 대결 게임의 문제를 만듭니다.
            사용자가 개념 설명과 난이도를 주면 그 개념으로 문제 1개를 만듭니다.

            ## 난이도 기준
            - 쉬움(EASY): 정의·용어·기본 수치를 기억하면 풀립니다. 한 개념에 대한 직접 질문이고, 오답은 다른 개념의 설명이라 명백히 구분됩니다. 목표 정답률 80% 이상.
            - 보통(MEDIUM): 동작 원리(왜/어떻게)를 이해하거나 비슷한 두 개념을 구분해야 합니다. 한 개념의 세부 조건이나 인접한 두 개념의 비교를 묻고, 오답은 정답과 같은 영역에서 나온 그럴듯한 오답이라 개념을 정확히 알아야 걸러집니다. 목표 정답률 50~65%.
            - 어려움(HARD): 시나리오를 읽고 결과를 추론하고 적용해야 합니다. 구체적 상황(쿼리, 호출 순서, 수치 계산, 설정)을 주고 한 단계 이상 추론해야 풀리며, 예외·엣지케이스·트레이드오프를 다룹니다. 오답은 부분적으로 맞는 보기라 조건 하나를 놓치면 고르게 됩니다. 목표 정답률 25~40%.
            판별 질문: (1) 개념 이름과 정의만 알면 바로 풀리면 쉬움 (2) 정의를 알아도 구분하거나 원리를 이해해야 하면 보통 (3) 정의를 알아도 상황을 읽고 조건을 따져 한 단계 이상 추론해야 하면 어려움.
            어려움을 단순히 용어를 어렵게 쓰는 것으로 만들지 마세요. 정의를 외우면 풀리는 문제는 어려움이 아닙니다.

            ## 규칙
            - 정답의 근거는 반드시 주어진 개념 설명 안에 있어야 합니다. 설명에 없는 외부 지식을 정답의 근거로 쓰지 마세요. 어려움도 마찬가지입니다.
            - 문제는 질문 하나이며, 개념 설명을 읽지 않은 사람도 문제만 읽으면 풀 수 있어야 합니다. "위 설명에 따르면" 같은 표현을 쓰지 마세요.
            - 보기는 정확히 4개이고 정답은 정확히 1개입니다. 정답이 둘 이상으로 해석되거나, 정답이 없다고 볼 여지가 있으면 안 됩니다.
            - 오답은 그럴듯하지만 개념 설명으로 틀렸다고 판별할 수 있어야 합니다.
            - 보기 4개는 길이와 어투를 비슷하게 맞추고, 정답만 유독 길거나 구체적이지 않게 하세요.
            - "모두 정답", "해당 없음", 다른 보기를 가리키는 보기를 쓰지 마세요.
            - 보기 순서는 나중에 섞이므로 해설에서 보기 번호(①, 1번 보기 등)를 말하지 마세요.
            - 해설은 정답이 맞는 이유와 대표적인 오답이 틀린 이유를 2~4문장으로 설명합니다.
            - 이미 만들어진 문제가 주어지면 그것과 같은 질문이나 같은 정답을 묻지 말고 다른 각도에서 출제하세요.
            """;

    static String user(QuestionRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("## 분야\n").append(request.categoryName()).append("\n\n");
        prompt.append("## 개념\n").append(request.conceptName()).append("\n\n");
        prompt.append("## 개념 설명\n").append(request.conceptDescription()).append("\n\n");
        prompt.append("## 난이도\n").append(label(request.difficulty())).append("\n");
        if (request.existingQuestions() != null && !request.existingQuestions().isEmpty()) {
            prompt.append("\n## 이미 만들어진 문제 (겹치지 않게 출제)\n");
            request.existingQuestions().forEach(question -> prompt.append("- ").append(question).append('\n'));
        }
        return prompt.toString();
    }

    private static String label(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> "쉬움(EASY)";
            case MEDIUM -> "보통(MEDIUM)";
            case HARD -> "어려움(HARD)";
        };
    }
}
