package com.react.poll.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 투표 결과의 비율·미응답 계산 규칙. */
public final class PollResultMath {
    private PollResultMath() { }

    /** part 가 whole 의 몇 %인지 소수 첫째 자리로 반올림한다. 분모가 0 이면 0.0. */
    public static double percentage(long part, long whole) {
        if (whole == 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(part * 100)
                .divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /** 미응답 = 전체 참여자 − 응답자. 미응답은 어떤 선택지로도 간주하지 않는다. */
    public static long unresponded(long totalParticipants, long respondedParticipants) {
        return totalParticipants - respondedParticipants;
    }
}
