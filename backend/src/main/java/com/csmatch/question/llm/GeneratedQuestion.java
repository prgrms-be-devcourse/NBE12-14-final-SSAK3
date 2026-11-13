package com.csmatch.question.llm;

import com.csmatch.question.Enum.Difficulty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * LLM 이 돌려주는 문제. 이 record 로 JSON 스키마를 만들어 응답 형식을 강제한다.
 * solvingSteps 는 문제를 쓰기 전에 풀이 단계를 먼저 적게 해서 난이도를 맞추는 용도이며 저장하지 않는다.
 */
public record GeneratedQuestion(
        @JsonPropertyDescription("정답에 이르는 풀이 단계를 한 줄씩. 개념 설명의 어떤 사실을 어떻게 적용하는지 쓴다. "
                + "정의를 그대로 떠올리면 풀리는 문제는 1단계다. 문제를 쓰기 전에 먼저 적는다.")
        List<String> solvingSteps,
        @JsonPropertyDescription("문제 본문. 질문 하나. 이 문제만 읽어도 풀 수 있어야 한다.")
        String content,
        @JsonPropertyDescription("보기 정확히 4개. 정답은 그중 1개. 네 보기의 글자 수 차이는 15% 이내.")
        List<Choice> choices,
        @JsonPropertyDescription("정답인 이유와 대표적인 오답이 틀린 이유. 보기 번호를 말하지 않는다.")
        String explanation) {

    public record Choice(
            @JsonPropertyDescription("보기 내용")
            String content,
            @JsonPropertyDescription("정답이면 true")
            boolean correct) {
    }

    static final int CHOICE_COUNT = 4;
    static final int MAX_CHOICE_LENGTH = 500;
    /** 어려움 문제가 가져야 하는 최소 풀이 단계 수. 정의·분류를 고르는 문제는 1단계다. */
    static final int HARD_MIN_STEPS = 2;
    /** 정답이 오답 평균 길이의 이 배수를 넘으면 길이만으로 정답이 드러난다고 본다. */
    static final double MAX_CORRECT_LENGTH_RATIO = 1.3;
    /** 보기가 짧으면 길이 비교가 의미 없으므로 정답이 이 글자 수 이상일 때만 비교한다. */
    static final int LENGTH_CHECK_MIN_LENGTH = 20;

    // 보기 순서는 서버가 섞으므로 해설이 보기 번호를 말하면 안 된다.
    private static final Pattern CHOICE_NUMBER = Pattern.compile("[①②③④]|[1-4]번\\s*보기|보기\\s*[1-4]\\s*번|정답은\\s*[1-4]\\s*번");
    private static final List<String> BANNED_CHOICES = List.of("모두 정답", "모두 맞", "해당 없음", "정답 없음", "위의 모든", "위 보기");

    /** 저장해도 되는 형태인지 검사한다. 문제가 있으면 이유를, 없으면 빈 값을 돌려준다. */
    public Optional<String> validate(Difficulty difficulty) {
        if (isBlank(content)) {
            return Optional.of("문제 본문이 비어 있습니다.");
        }
        if (isBlank(explanation)) {
            return Optional.of("해설이 비어 있습니다.");
        }
        if (choices == null || choices.size() != CHOICE_COUNT) {
            return Optional.of("보기가 4개가 아닙니다.");
        }
        if (choices.stream().filter(Choice::correct).count() != 1) {
            return Optional.of("정답이 정확히 1개가 아닙니다.");
        }
        HashSet<String> seen = new HashSet<>();
        for (Choice choice : choices) {
            if (choice == null || isBlank(choice.content())) {
                return Optional.of("비어 있는 보기가 있습니다.");
            }
            String text = choice.content().trim();
            if (text.length() > MAX_CHOICE_LENGTH) {
                return Optional.of("보기가 " + MAX_CHOICE_LENGTH + "자를 넘습니다.");
            }
            if (!seen.add(text)) {
                return Optional.of("같은 보기가 있습니다.");
            }
            if (BANNED_CHOICES.stream().anyMatch(text::contains)) {
                return Optional.of("허용하지 않는 보기 표현이 있습니다.");
            }
        }
        if (CHOICE_NUMBER.matcher(explanation).find()) {
            return Optional.of("해설이 보기 번호를 말하고 있습니다.");
        }
        if (correctChoiceTooLong()) {
            return Optional.of("정답 보기가 오답보다 눈에 띄게 깁니다.");
        }
        if (difficulty == Difficulty.HARD && stepCount() < HARD_MIN_STEPS) {
            return Optional.of("어려움 문제의 풀이 단계가 " + HARD_MIN_STEPS + "개 미만입니다.");
        }
        return Optional.empty();
    }

    private boolean correctChoiceTooLong() {
        int correctLength = choices.stream().filter(Choice::correct).findFirst().orElseThrow().content().trim().length();
        if (correctLength < LENGTH_CHECK_MIN_LENGTH) {
            return false;
        }
        double othersAverage = choices.stream().filter(choice -> !choice.correct())
                .mapToInt(choice -> choice.content().trim().length()).average().orElse(correctLength);
        return correctLength > othersAverage * MAX_CORRECT_LENGTH_RATIO;
    }

    private long stepCount() {
        return solvingSteps == null ? 0 : solvingSteps.stream().filter(step -> !isBlank(step)).count();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
