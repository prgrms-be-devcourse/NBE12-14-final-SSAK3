package com.csmatch.practice;

import com.csmatch.global.exception.ErrorCode;
import com.csmatch.practice.dto.PracticeDto;
import com.csmatch.practice.service.PracticeService;
import com.csmatch.question.Enum.Difficulty;
import com.csmatch.question.Enum.QuestionSource;
import com.csmatch.question.entity.Question;
import com.csmatch.question.repository.CategoryRepository;
import com.csmatch.question.repository.QuestionRepository;
import com.csmatch.user.entity.User;
import com.csmatch.user.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://${TEST_MYSQL_HOST:localhost}:${TEST_MYSQL_PORT:3306}/csmatch_practice_test?serverTimezone=Asia/Seoul&characterEncoding=UTF-8",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PracticeIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PracticeService service;
    @Autowired UserRepository users;
    @Autowired QuestionRepository questions;
    @Autowired CategoryRepository categories;
    Long userId;
    Long otherId;

    @BeforeEach
    void setUp() {
        // 기존 Question 매핑은 DB 기본값에 의존하므로 schema.sql의 기본값을 테스트 DDL에도 맞춘다.
        jdbc.execute("ALTER TABLE questions MODIFY created_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP");
        for (String table : List.of("saved_questions", "practice_choices", "practice_answers", "practice_sessions",
                "practice_progress", "question_choices", "questions", "concepts", "categories", "users")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("INSERT INTO categories(category_id, name) VALUES (1, '네트워크'), (2, '운영체제')");
        userId = users.saveAndFlush(User.create("practice@example.com", "test-hash", "연습회원")).getUserId();
        otherId = users.saveAndFlush(User.create("other@example.com", "test-hash", "다른회원")).getUserId();
    }

    @Test
    void fourteenQuestionsAreExhaustedInTenThenFourAndRestartPreservesHistory() throws Exception {
        addQuestions(1, 14);
        long first = start(null, 201);
        Set<Long> seen = finish(first);
        assertThat(seen).hasSize(10);
        var firstResult = data(get("/api/v1/practice/sessions/" + first), 200);
        assertThat(firstResult.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(firstResult.path("correctCount").asInt()).isEqualTo(10);
        assertThat(firstResult.path("endedAt").asText()).endsWith("+09:00");
        long second = start(null, 201);
        assertThat(service.detail(userId, second).questionCount()).isEqualTo(4);
        Set<Long> rest = finish(second);
        assertThat(rest).doesNotContainAnyElementsOf(seen);
        seen.addAll(rest);
        assertThat(seen).hasSize(14);
        assertThat(service.getProgress(userId, null).completed()).isTrue();
        assertThat(service.getProgress(userId, null).remainingCount()).isZero();
        assertError(post("/api/v1/practice/sessions").content("{}"), 404, "NO_MORE_QUESTIONS");
        data(post("/api/v1/practice/restart"), 200);
        assertThat(service.getProgress(userId, null).answeredCount()).isZero();
        assertThat(service.getProgress(userId, null).learningCycle()).isEqualTo(2);
        assertThat(service.history(userId, first, false)).hasSize(10);
        assertThat(start(null, 201)).isNotEqualTo(first);
        assertThat(users.findById(userId).orElseThrow().getRating()).isEqualTo(1000);
    }

    @Test
    void categoryProgressIsSharedAndAbandonedUnansweredQuestionsReturn() throws Exception {
        addQuestions(1, 4);
        addQuestions(2, 3);
        long session = start(1, 201);
        var first = service.next(userId, session).orElseThrow();
        assertThat(first.question().categoryId()).isEqualTo(1);
        long answered = first.question().questionId();
        submit(session, answered, false);
        assertThat(service.getProgress(userId, 1).answeredCount()).isEqualTo(1);
        assertThat(service.getProgress(userId, null).answeredCount()).isEqualTo(1);
        data(post("/api/v1/practice/sessions/" + session + "/end"), 200);
        assertThat(service.detail(userId, session).status()).isEqualTo("ABANDONED");
        assertThat(service.detail(userId, session).correctCount()).isZero();
        var history = data(get("/api/v1/practice/sessions/" + session + "/questions"), 200);
        assertThat(history.size()).isEqualTo(4);
        assertThat(history.get(0).path("correctChoiceId").isNumber()).isTrue();
        assertThat(history.get(1).path("correctChoiceId").isNull()).isTrue();
        assertThat(history.get(1).path("explanation").isNull()).isTrue();
        assertThat(data(get("/api/v1/practice/sessions/" + session + "/questions?wrongOnly=true"), 200).size()).isEqualTo(1);
        long next = start(null, 201);
        assertThat(service.detail(userId, next).questionCount()).isEqualTo(6);
        assertThat(finish(next)).doesNotContain(answered);
    }

    @Test
    void newQuestionsAreAddedAfterTheCurrentBatchAndAfterCompletion() throws Exception {
        addQuestions(1, 1);
        long session = start(null, 201);
        addQuestions(1, 2);
        assertThat(finish(session)).hasSize(1);
        assertThat(service.getProgress(userId, null).remainingCount()).isEqualTo(2);
        finish(start(null, 201));
        assertThat(service.getProgress(userId, null).completed()).isTrue();
        addQuestions(2, 1);
        assertThat(service.getProgress(userId, null).completed()).isFalse();
        assertThat(finish(start(null, 201))).hasSize(1);
    }

    @Test
    void tabsResumeOneSessionAndInvalidAnswersCannotAdvanceIt() throws Exception {
        addQuestions(1, 3);
        long session = start(1, 201);
        assertThat(start(1, 200)).isEqualTo(session);
        assertThat(data(get("/api/v1/practice/sessions/current"), 200).path("sessionId").asLong()).isEqualTo(session);
        assertError(post("/api/v1/practice/sessions").content("{}"), 409, "PRACTICE_IN_PROGRESS");
        assertError(post("/api/v1/practice/restart"), 409, "PRACTICE_IN_PROGRESS");
        var next = service.next(userId, session).orElseThrow();
        assertThat(service.next(userId, session)).contains(next);
        var later = service.history(userId, session, false).get(1);
        assertError(answerRequest(session, later.question().questionId(), later.choices().getFirst().choiceId()),
                400, "VALIDATION_FAILED");
        assertError(answerRequest(session, next.question().questionId(), Long.MAX_VALUE), 400, "VALIDATION_FAILED");
        assertError(answerRequest(session, next.question().questionId(), later.choices().getFirst().choiceId()),
                400, "VALIDATION_FAILED");
        var original = data(get("/api/v1/practice/sessions/" + session + "/question"), 200);
        assertThat(original.toString()).doesNotContain("explanation", "correctChoiceId", "isCorrect");
        submit(session, next.question().questionId(), true);
        assertError(answerRequest(session, next.question().questionId(), next.choices().getFirst().choiceId()),
                409, "ALREADY_ANSWERED");
        assertThat(service.next(userId, session).orElseThrow().question().questionId()).isNotEqualTo(next.question().questionId());
    }

    @Test
    void simultaneousStartsAndSubmissionsOnlyApplyOnce() throws Exception {
        addQuestions(1, 3);
        var starts = race(() -> service.start(userId, null).orElseThrow());
        assertThat(starts.get(0).session().sessionId()).isEqualTo(starts.get(1).session().sessionId());
        assertThat(starts.stream().filter(PracticeService.StartResult::created)).hasSize(1);
        long session = starts.getFirst().session().sessionId();
        var next = service.next(userId, session).orElseThrow();
        var request = new PracticeDto.AnswerRequest(next.question().questionId(), correctChoice(next.question().questionId()));
        var results = race(() -> service.submit(userId, session, request));
        assertThat(results.stream().filter(value -> value.error() == null)).hasSize(1);
        assertThat(results.stream().filter(value -> value.error() == ErrorCode.ALREADY_ANSWERED)).hasSize(1);
        assertThat(service.detail(userId, session).correctCount()).isEqualTo(1);
        assertThat(service.getProgress(userId, null).answeredCount()).isEqualTo(1);
    }

    @Test
    void hiddenUnansweredQuestionsAreSkippedAndAllHiddenBatchFinishesEvenOn404() throws Exception {
        addQuestions(1, 3);
        long session = start(null, 201);
        long first = service.next(userId, session).orElseThrow().question().questionId();
        submit(session, first, true);
        jdbc.update("UPDATE questions SET status = 'HIDDEN'");
        assertError(get("/api/v1/practice/sessions/" + session + "/question"), 404, "NO_MORE_QUESTIONS");
        assertThat(jdbc.queryForObject("SELECT status FROM practice_sessions WHERE session_id = ?", String.class, session))
                .isEqualTo("COMPLETED");
        var detail = service.detail(userId, session);
        assertThat(detail.questionCount()).isEqualTo(1);
        assertThat(detail.correctCount()).isEqualTo(1);
        var history = service.history(userId, session, false);
        assertThat(history).allMatch(PracticeDto.HistoryItem::hidden);
        assertThat(history.getFirst().correctChoiceId()).isNotNull();
        assertThat(history.subList(1, 3)).allMatch(item -> item.status().equals("SKIPPED") && item.correctChoiceId() == null);
        assertError(answerRequest(session, first, correctChoice(first)), 409, "SESSION_FINISHED");
    }

    @Test
    void snapshotSurvivesEditsWhileSavedReviewUsesLatestAndHidesUnavailableContent() throws Exception {
        addQuestions(1, 2);
        long session = start(null, 201);
        var next = service.next(userId, session).orElseThrow();
        long questionId = next.question().questionId();
        long choiceId = correctChoice(questionId);
        jdbc.update("UPDATE questions SET content = '수정된 본문', explanation = '수정된 해설' WHERE question_id = ?", questionId);
        jdbc.update("UPDATE question_choices SET content = '수정된 보기', is_correct = false WHERE question_id = ?", questionId);
        jdbc.update("UPDATE question_choices SET is_correct = true WHERE question_id = ? AND choice_no = 2", questionId);
        var result = data(answerRequest(session, questionId, choiceId), 200);
        assertThat(result.path("correct").asBoolean()).isTrue();
        assertThat(result.path("explanation").asText()).isEqualTo("원래 해설");
        assertThat(service.history(userId, session, false).getFirst().question().content()).isEqualTo(next.question().content());
        var save = post("/api/v1/review-questions").content(mapper.writeValueAsString(Map.of(
                "questionId", questionId, "practiceSessionId", session)));
        data(save, 201);
        var review = data(get("/api/v1/review-questions"), 200).path("items").get(0);
        assertThat(review.path("content").asText()).isEqualTo("수정된 본문");
        assertThat(review.path("explanation").asText()).isEqualTo("수정된 해설");
        assertError(save, 409, "ALREADY_SAVED");
        jdbc.update("UPDATE questions SET status = 'HIDDEN' WHERE question_id = ?", questionId);
        var hidden = data(get("/api/v1/review-questions"), 200).path("items").get(0);
        assertThat(hidden.path("available").asBoolean()).isFalse();
        assertThat(hidden.path("content").isNull()).isTrue();
        assertThat(hidden.path("explanation").isNull()).isTrue();
        assertThat(hidden.path("correctChoiceId").isNull()).isTrue();
        assertThat(hidden.path("choices").size()).isZero();
    }

    @Test
    void ownershipAndAuthenticationProtectHistorySubmissionAndReview() throws Exception {
        addQuestions(1, 2);
        long session = service.start(otherId, null).orElseThrow().session().sessionId();
        long questionId = service.next(otherId, session).orElseThrow().question().questionId();
        mvc.perform(get("/api/v1/practice/progress")).andExpect(status().isUnauthorized());
        assertError(get("/api/v1/practice/sessions/" + session), 403, "FORBIDDEN");
        assertError(get("/api/v1/practice/sessions/" + session + "/questions"), 403, "FORBIDDEN");
        assertError(answerRequest(session, questionId, correctChoice(questionId)), 403, "FORBIDDEN");
        assertError(post("/api/v1/practice/sessions/" + session + "/end"), 403, "FORBIDDEN");
        assertError(post("/api/v1/review-questions").content(mapper.writeValueAsString(Map.of(
                "questionId", questionId, "practiceSessionId", session))), 403, "FORBIDDEN");
        long own = start(null, 201);
        long pending = service.next(userId, own).orElseThrow().question().questionId();
        assertError(post("/api/v1/review-questions").content(mapper.writeValueAsString(Map.of(
                "questionId", pending, "practiceSessionId", own))), 403, "FORBIDDEN");
        assertError(get("/api/v1/practice/sessions?size=101"), 400, "VALIDATION_FAILED");
        assertError(post("/api/v1/practice/sessions").content("{\"categoryId\":0}"), 400, "VALIDATION_FAILED");
        assertThat(service.list(userId, 0, 20).totalElements()).isEqualTo(1);
    }

    @Test
    void draftQuestionsAndEmptyCategoryDoNotStartPractice() throws Exception {
        addQuestions(1, 1);
        jdbc.update("UPDATE questions SET status = 'DRAFT'");
        assertError(post("/api/v1/practice/sessions").content("{}"), 404, "NO_MORE_QUESTIONS");
        assertThat(service.getProgress(userId, null).completed()).isFalse();
        assertThat(service.getProgress(userId, null).totalCount()).isZero();
        assertError(post("/api/v1/practice/sessions").content("{\"categoryId\":99}"), 404, "NOT_FOUND");
    }

    @Test
    void choiceOrderSurvivesDatabaseReloadResumeAndHistory() throws Exception {
        addQuestions(1, 1);
        long session = start(null, 201);
        var first = service.next(userId, session).orElseThrow();
        long questionId = first.question().questionId();
        var originalIds = jdbc.queryForList(
                "SELECT choice_id FROM question_choices WHERE question_id = ? ORDER BY choice_no", Long.class, questionId);
        assertThat(first.choices()).extracting(PracticeDto.Choice::choiceId).containsExactlyInAnyOrderElementsOf(originalIds);
        assertThat(first.choices()).extracting(PracticeDto.Choice::choiceNo).containsExactly(1, 2, 3, 4);
        var storedIds = jdbc.queryForList("""
                SELECT c.choice_id FROM practice_choices c
                JOIN practice_answers a ON a.answer_id = c.answer_id
                WHERE a.session_id = ? AND a.question_id = ? ORDER BY c.choice_no
                """, Long.class, session, questionId);
        assertThat(first.choices()).extracting(PracticeDto.Choice::choiceId).containsExactlyElementsOf(storedIds);
        assertThat(start(null, 200)).isEqualTo(session);
        assertThat(service.next(userId, session)).contains(first);
        assertThat(service.history(userId, session, false).getFirst().choices()).isEqualTo(first.choices());
        var result = data(answerRequest(session, questionId, correctChoice(questionId)), 200);
        assertThat(result.path("correct").asBoolean()).isTrue();
        assertThat(result.path("correctChoiceId").asLong()).isEqualTo(correctChoice(questionId));
        assertThat(service.history(userId, session, false).getFirst().choices()).isEqualTo(first.choices());
        assertThat(jdbc.queryForList("SELECT choice_id FROM question_choices WHERE question_id = ? ORDER BY choice_no",
                Long.class, questionId)).isEqualTo(originalIds);
    }

    private void addQuestions(int categoryId, int count) {
        var category = categories.findById(categoryId).orElseThrow();
        for (int i = 0; i < count; i++) {
            var question = Question.create(category, Difficulty.EASY, "문제 " + categoryId + "-" + i, "원래 해설",
                    QuestionSource.MANUAL, null, null, List.of(new Question.ChoiceInput("정답", true),
                            new Question.ChoiceInput("오답1", false), new Question.ChoiceInput("오답2", false),
                            new Question.ChoiceInput("오답3", false)));
            question.activate();
            questions.saveAndFlush(question);
        }
    }

    private long start(Integer category, int expected) throws Exception {
        String body = category == null ? "{}" : mapper.writeValueAsString(Map.of("categoryId", category));
        return data(post("/api/v1/practice/sessions").content(body), expected).path("sessionId").asLong();
    }

    private Set<Long> finish(long session) throws Exception {
        Set<Long> seen = new HashSet<>();
        while (true) {
            var next = service.next(userId, session);
            if (next.isEmpty()) {
                return seen;
            }
            long id = next.get().question().questionId();
            assertThat(seen.add(id)).isTrue();
            submit(session, id, true);
        }
    }

    private long correctChoice(long questionId) {
        return jdbc.queryForObject("SELECT choice_id FROM question_choices WHERE question_id = ? AND is_correct = true",
                Long.class, questionId);
    }

    private void submit(long session, long questionId, boolean correct) throws Exception {
        long choice = correct ? correctChoice(questionId) : jdbc.queryForObject(
                "SELECT MIN(choice_id) FROM question_choices WHERE question_id = ? AND is_correct = false", Long.class, questionId);
        data(answerRequest(session, questionId, choice), 200);
    }

    private MockHttpServletRequestBuilder answerRequest(long session, long question, long choice) throws Exception {
        return post("/api/v1/practice/sessions/" + session + "/answers")
                .content(mapper.writeValueAsString(Map.of("questionId", question, "choiceId", choice)));
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
        return request.contentType(MediaType.APPLICATION_JSON).with(authentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")))));
    }

    private JsonNode data(MockHttpServletRequestBuilder request, int status) throws Exception {
        return mapper.readTree(mvc.perform(authenticated(request)).andExpect(status().is(status))
                .andReturn().getResponse().getContentAsString()).path("data");
    }

    private void assertError(MockHttpServletRequestBuilder request, int status, String code) throws Exception {
        mvc.perform(authenticated(request)).andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code));
    }

    private <T> List<T> race(Callable<T> action) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<T> task = () -> { barrier.await(5, TimeUnit.SECONDS); return action.call(); };
            var first = executor.submit(task);
            var second = executor.submit(task);
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
    }
}
