package com.csmatch.question.Enum;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Difficulty {
    EASY(20, -10), MEDIUM(30, -15), HARD(40,-20);
    private final int correctScore; // 정답 시 점수
    private final int wrongScoreDelta; // 오답 시 점수 변화

}
