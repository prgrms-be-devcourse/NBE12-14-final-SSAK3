package com.react.poll;

import com.react.poll.service.PollResultMath;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 결과 비율 계산: DB 없이 숫자 규칙만 확인한다. */
class PollResultMathTest {

    @Test
    void 명세_예시의_비율을_그대로_계산한다() {
        // 응답자 12명, 전체 참여자 20명, 선택지별 6·4·2명
        assertThat(PollResultMath.percentage(6, 12)).isEqualTo(50.0);
        assertThat(PollResultMath.percentage(4, 12)).isEqualTo(33.3);
        assertThat(PollResultMath.percentage(2, 12)).isEqualTo(16.7);
        assertThat(PollResultMath.percentage(6, 20)).isEqualTo(30.0);
        assertThat(PollResultMath.percentage(12, 20)).isEqualTo(60.0);
    }

    @Test
    void 분모가_0이면_0점0이다() {
        assertThat(PollResultMath.percentage(0, 0)).isEqualTo(0.0);
    }

    @Test
    void 소수_첫째_자리까지_반올림한다() {
        assertThat(PollResultMath.percentage(1, 16)).isEqualTo(6.3); // 6.25 → 반올림
        assertThat(PollResultMath.percentage(2, 3)).isEqualTo(66.7);
        assertThat(PollResultMath.percentage(1, 3)).isEqualTo(33.3);
    }

    @Test
    void 모두_응답하면_100이고_아무도_안_하면_0이다() {
        assertThat(PollResultMath.percentage(7, 7)).isEqualTo(100.0);
        assertThat(PollResultMath.percentage(0, 7)).isEqualTo(0.0);
    }

    @Test
    void 미응답은_전체에서_응답을_뺀_수이다() {
        assertThat(PollResultMath.unresponded(20, 12)).isEqualTo(8);
        assertThat(PollResultMath.unresponded(0, 0)).isZero();
    }
}
