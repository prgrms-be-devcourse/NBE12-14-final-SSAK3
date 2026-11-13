package com.csmatch.question.llm;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** LLM 이 돌려주는 문제. 이 record 로 JSON 스키마를 만들어 응답 형식을 강제한다. */
public record GeneratedQuestion(
        @JsonPropertyDescription("문제 본문. 질문 하나. 이 문제만 읽어도 풀 수 있어야 한다.")
        String content,
        @JsonPropertyDescription("보기 정확히 4개. 정답은 그중 1개.")
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

    // 보기 순서는 서버가 섞으므로 해설이 보기 번호를 말하면 안 된다.
    private static final Pattern CHOICE_NUMBER = Pattern.compile("[①②③④]|[1-4]번\\s*보기|보기\\s*[1-4]\\s*번|정답은\\s*[1-4]\\s*번");
    private static final List<String> BANNED_CHOICES = List.of("모두 정답", "모두 맞", "해당 없음", "정답 없음", "위의 모든", "위 보기");

    /** 저장해도 되는 형태인지 검사한다. 문제가 있으면 이유를, 없으면 빈 값을 돌려준다. */
    public Optional<String> validate() {
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
        return Optional.empty();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
