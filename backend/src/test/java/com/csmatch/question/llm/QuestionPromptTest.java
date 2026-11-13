package com.csmatch.question.llm;

import com.csmatch.question.Enum.Difficulty;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionPromptTest {

    @Test
    void 사용자_프롬프트에_분야_개념_설명_난이도가_들어간다() {
        String prompt = QuestionPrompt.user(new QuestionRequest("네트워크", "TCP 3-way handshake",
                "SYN, SYN+ACK, ACK 순서로 연결을 맺는다.", Difficulty.HARD, List.of()));

        assertThat(prompt).contains("네트워크", "TCP 3-way handshake", "SYN, SYN+ACK, ACK", "어려움(HARD)");
        assertThat(prompt).doesNotContain("이미 만들어진 문제");
    }

    @Test
    void 이미_만들어진_문제가_있으면_겹치지_않게_목록으로_넘긴다() {
        String prompt = QuestionPrompt.user(new QuestionRequest("네트워크", "DNS", "도메인을 IP로 바꾼다.",
                Difficulty.EASY, List.of("DNS의 역할은?", "CNAME 레코드란?")));

        assertThat(prompt).contains("이미 만들어진 문제", "- DNS의 역할은?", "- CNAME 레코드란?", "쉬움(EASY)");
    }

    @Test
    void 난이도마다_다른_표기를_쓴다() {
        assertThat(QuestionPrompt.user(request(Difficulty.EASY))).contains("쉬움(EASY)");
        assertThat(QuestionPrompt.user(request(Difficulty.MEDIUM))).contains("보통(MEDIUM)");
        assertThat(QuestionPrompt.user(request(Difficulty.HARD))).contains("어려움(HARD)");
    }

    @Test
    void 시스템_프롬프트에_난이도_기준과_핵심_규칙이_있다() {
        assertThat(QuestionPrompt.SYSTEM).contains("쉬움(EASY)", "보통(MEDIUM)", "어려움(HARD)")
                .contains("개념 설명 안에 있어야", "정확히 4개", "정확히 1개", "보기 번호");
    }

    @Test
    void 시스템_프롬프트는_요청마다_달라지지_않는다() {
        // 프롬프트 캐시는 앞부분이 byte 단위로 같아야 적용된다. 날짜·ID 같은 값이 들어가면 안 된다.
        assertThat(QuestionPrompt.SYSTEM).doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}");
    }

    private static QuestionRequest request(Difficulty difficulty) {
        return new QuestionRequest("분야", "개념", "설명", difficulty, List.of());
    }
}
