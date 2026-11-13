package com.csmatch.admin;

import com.csmatch.admin.dto.QuestionGenerationDto;
import com.csmatch.admin.service.QuestionGenerationService;
import com.csmatch.question.Enum.Difficulty;
import com.csmatch.question.Enum.QuestionSource;
import com.csmatch.question.entity.Question;
import com.csmatch.question.llm.GeneratedQuestion;
import com.csmatch.question.llm.LlmException;
import com.csmatch.question.llm.QuestionGenerator;
import com.csmatch.question.llm.QuestionRequest;
import com.csmatch.question.repository.CategoryRepository;
import com.csmatch.question.repository.ConceptRepository;
import com.csmatch.question.repository.QuestionRepository;
import com.csmatch.user.entity.User;
import com.csmatch.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class QuestionGenerationIntegrationTest {
    private static final String URL = "/api/v1/admin/questions/generate";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired QuestionRepository questions;
    @Autowired CategoryRepository categories;
    @Autowired ConceptRepository concepts;
    @Autowired QuestionGenerationService service;
    @MockitoBean QuestionGenerator generator;

    Long adminId;
    Long userId;

    @BeforeEach
    void setUp() {
        // 이전 테스트의 생성 작업이 끝난 뒤에 시작한다.
        await().atMost(10, TimeUnit.SECONDS).until(() -> !"RUNNING".equals(service.status().status()));
        // Question.createdAt 은 DB 기본값에 의존하므로 schema 의 기본값을 테스트 DDL 에도 맞춘다.
        jdbc.execute("ALTER TABLE questions MODIFY created_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP");
        jdbc.execute("ALTER TABLE concepts MODIFY created_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP");
        for (String table : List.of("question_choices", "questions", "concepts", "categories", "users")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("INSERT INTO categories(category_id, name) VALUES (1, '네트워크'), (2, '운영체제')");
        jdbc.update("INSERT INTO concepts(concept_id, category_id, name, description) VALUES "
                + "(1, 1, 'TCP', '설명1'), (2, 1, 'UDP', '설명2'), (3, 1, 'DNS', '설명3'), (4, 2, '프로세스', '설명4')");
        adminId = users.saveAndFlush(User.create("admin@example.com", "test-hash", "관리자")).getUserId();
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE user_id = ?", adminId);
        userId = users.saveAndFlush(User.create("user@example.com", "test-hash", "회원")).getUserId();

        when(generator.isConfigured()).thenReturn(true);
        when(generator.defaultModel()).thenReturn("claude-sonnet-5-5");
        when(generator.generate(any())).thenReturn(valid());
    }

    @Test
    void 생성하면_202를_돌려주고_DRAFT_AI_문제가_개념과_함께_저장된다() throws Exception {
        mvc.perform(post(URL).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":3,\"categoryId\":1}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.requested").value(3));

        awaitFinished();

        QuestionGenerationDto.Status done = service.status();
        assertThat(done.succeeded()).isEqualTo(3);
        assertThat(done.failed()).isZero();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, source, concept_id, created_by, difficulty FROM questions");
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("status")).isEqualTo("DRAFT");
            assertThat(row.get("source")).isEqualTo("AI");
            assertThat(row.get("concept_id")).isNotNull();
            assertThat(row.get("created_by")).isNull();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_choices", Integer.class)).isEqualTo(12);
    }

    @Test
    void 난이도를_균등하게_나누고_남는_것은_보통이다() throws Exception {
        start(7, 1);
        awaitFinished();

        assertThat(countByDifficulty("EASY")).isEqualTo(2);
        assertThat(countByDifficulty("HARD")).isEqualTo(2);
        assertThat(countByDifficulty("MEDIUM")).isEqualTo(3);
    }

    @Test
    void 연결된_문제가_가장_적은_개념부터_고른다() throws Exception {
        existingQuestion(1, "TCP 기존 문제");
        existingQuestion(2, "UDP 기존 문제");

        start(1, 1);
        awaitFinished();

        ArgumentCaptor<QuestionRequest> captor = ArgumentCaptor.forClass(QuestionRequest.class);
        verify(generator).generate(captor.capture());
        assertThat(captor.getValue().conceptName()).isEqualTo("DNS");
        assertThat(captor.getValue().categoryName()).isEqualTo("네트워크");
        assertThat(captor.getValue().conceptDescription()).isEqualTo("설명3");
    }

    @Test
    void 같은_개념의_기존_문제를_프롬프트에_넘긴다() throws Exception {
        existingQuestion(4, "프로세스란 무엇인가?");

        start(1, 2);
        awaitFinished();

        ArgumentCaptor<QuestionRequest> captor = ArgumentCaptor.forClass(QuestionRequest.class);
        verify(generator).generate(captor.capture());
        assertThat(captor.getValue().existingQuestions()).containsExactly("프로세스란 무엇인가?");
    }

    @Test
    void 개념보다_많이_요청하면_개념을_돌려_쓴다() throws Exception {
        start(5, 2);
        awaitFinished();

        assertThat(service.status().succeeded()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT concept_id) FROM questions", Integer.class)).isEqualTo(1);
    }

    @Test
    void 일시적인_실패는_다시_시도해_성공한다() throws Exception {
        when(generator.generate(any()))
                .thenThrow(new LlmException("일시 오류"))
                .thenThrow(new LlmException("일시 오류"))
                .thenReturn(valid());

        start(1, 1);
        awaitFinished();

        assertThat(service.status().succeeded()).isEqualTo(1);
        assertThat(service.status().failed()).isZero();
        verify(generator, times(3)).generate(any());
    }

    @Test
    void 쓸_수_없는_문제는_최대_시도_횟수까지만_다시_하고_저장하지_않는다() throws Exception {
        when(generator.generate(any())).thenReturn(new GeneratedQuestion(List.of("풀이"), "문제",
                List.of(new GeneratedQuestion.Choice("a", true), new GeneratedQuestion.Choice("b", false)), "해설"));

        start(1, 1);
        awaitFinished();

        assertThat(service.status().succeeded()).isZero();
        assertThat(service.status().failed()).isEqualTo(1);
        verify(generator, times(3)).generate(any());
        assertThat(questions.count()).isZero();
    }

    @Test
    void 어려움_문제의_풀이_단계가_부족하면_다시_시도하고_끝내_실패하면_건너뛴다() throws Exception {
        when(generator.generate(any())).thenReturn(new GeneratedQuestion(List.of("정의를 떠올린다"), "문제 본문",
                List.of(new GeneratedQuestion.Choice("정답 보기", true), new GeneratedQuestion.Choice("오답 1", false),
                        new GeneratedQuestion.Choice("오답 2", false), new GeneratedQuestion.Choice("오답 3", false)),
                "해설"));

        start(3, 1);
        awaitFinished();

        // 쉬움·보통은 1번에 저장, 어려움은 3번 시도 후 실패
        assertThat(service.status().succeeded()).isEqualTo(2);
        assertThat(service.status().failed()).isEqualTo(1);
        verify(generator, times(5)).generate(any());
        assertThat(countByDifficulty("HARD")).isZero();
    }

    @Test
    void 정답이_눈에_띄게_길면_다시_생성한다() throws Exception {
        GeneratedQuestion longCorrect = new GeneratedQuestion(List.of("조건 A", "조건 B"), "문제 본문",
                List.of(new GeneratedQuestion.Choice("가".repeat(60), true), new GeneratedQuestion.Choice("나".repeat(20), false),
                        new GeneratedQuestion.Choice("다".repeat(20), false), new GeneratedQuestion.Choice("라".repeat(20), false)),
                "해설");
        when(generator.generate(any())).thenReturn(longCorrect).thenReturn(valid());

        start(1, 1);
        awaitFinished();

        assertThat(service.status().succeeded()).isEqualTo(1);
        verify(generator, times(2)).generate(any());
    }

    @Test
    void 인증_오류처럼_다시_해도_소용없는_실패는_작업_전체를_멈춘다() throws Exception {
        when(generator.generate(any())).thenThrow(new LlmException("인증 실패", true, null));

        start(3, 1);
        awaitFinished();

        verify(generator, times(1)).generate(any());
        assertThat(service.status().succeeded()).isZero();
        assertThat(service.status().failed()).isEqualTo(1);
        assertThat(questions.count()).isZero();
    }

    @Test
    void 정답_위치가_섞여서_저장된다() throws Exception {
        start(30, 1);
        awaitFinished();

        assertThat(questions.count()).isEqualTo(30);
        Set<Integer> correctPositions = new HashSet<>();
        jdbc.queryForList("SELECT choice_no FROM question_choices WHERE is_correct = 1")
                .forEach(row -> correctPositions.add(((Number) row.get("choice_no")).intValue()));
        // 생성 결과는 항상 첫 번째가 정답이다. 30개가 모두 같은 위치일 확률은 사실상 0이다.
        assertThat(correctPositions.size()).isGreaterThan(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT question_id FROM question_choices WHERE is_correct = 1 "
                        + "GROUP BY question_id HAVING COUNT(*) = 1) t", Integer.class)).isEqualTo(30);
    }

    @Test
    void 실행_중에_다시_요청하면_409이고_끝나면_다시_실행할_수_있다() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(generator.generate(any())).thenAnswer(invocation -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return valid();
        });

        start(1, 1);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            mvc.perform(post(URL).with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"count\":1}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("GENERATION_IN_PROGRESS"));
            mvc.perform(get(URL + "/status").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("RUNNING"))
                    .andExpect(jsonPath("$.data.requested").value(1));
        } finally {
            release.countDown();
        }
        awaitFinished();

        when(generator.generate(any())).thenReturn(valid());
        start(1, 1);
        awaitFinished();
        assertThat(questions.count()).isEqualTo(2);
    }

    @Test
    void 모델을_생략하면_기본_모델로_생성하고_상태에_모델이_나온다() throws Exception {
        mvc.perform(post(URL).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":1,\"categoryId\":1}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.model").value("claude-sonnet-5-5"));
        awaitFinished();

        ArgumentCaptor<QuestionRequest> captor = ArgumentCaptor.forClass(QuestionRequest.class);
        verify(generator).generate(captor.capture());
        assertThat(captor.getValue().model()).isEqualTo("claude-sonnet-5-5");
        mvc.perform(get(URL + "/status").with(admin()))
                .andExpect(jsonPath("$.data.model").value("claude-sonnet-5-5"));
    }

    @Test
    void 허용된_모델을_고르면_그_모델로_호출한다() throws Exception {
        mvc.perform(post(URL).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":3,\"categoryId\":1,\"model\":\"claude-haiku-5-5\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.model").value("claude-haiku-5-5"));
        awaitFinished();

        ArgumentCaptor<QuestionRequest> captor = ArgumentCaptor.forClass(QuestionRequest.class);
        verify(generator, times(3)).generate(captor.capture());
        assertThat(captor.getAllValues()).extracting(QuestionRequest::model).containsOnly("claude-haiku-5-5");
        assertThat(service.status().model()).isEqualTo("claude-haiku-5-5");
    }

    @Test
    void 모델_앞뒤_공백은_무시한다() throws Exception {
        mvc.perform(post(URL).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":1,\"categoryId\":1,\"model\":\" claude-opus-5-5 \"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.model").value("claude-opus-5-5"));
        awaitFinished();
    }

    @Test
    void 허용되지_않은_모델이면_400이고_호출하지_않으며_실행_중_상태도_남기지_않는다() throws Exception {
        for (String model : List.of("claude-fable-5-1", "gpt-4o", "", " ", "claude-haiku-5-5; drop")) {
            mvc.perform(post(URL).with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"count\":1,\"model\":\"" + model + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }

        verify(generator, times(0)).generate(any());
        start(1, 1);
        awaitFinished();
        assertThat(questions.count()).isEqualTo(1);
    }

    @Test
    void 모델_이름이_너무_길면_400이다() throws Exception {
        mvc.perform(post(URL).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":1,\"model\":\"" + "a".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 상태_조회는_진행_결과를_돌려준다() throws Exception {
        start(2, 1);
        awaitFinished();

        mvc.perform(get(URL + "/status").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FINISHED"))
                .andExpect(jsonPath("$.data.requested").value(2))
                .andExpect(jsonPath("$.data.succeeded").value(2))
                .andExpect(jsonPath("$.data.failed").value(0))
                .andExpect(jsonPath("$.data.startedAt").isString())
                .andExpect(jsonPath("$.data.finishedAt").isString());
    }

    @Test
    void API_키가_없으면_503이고_LLM을_호출하지_않는다() throws Exception {
        when(generator.isConfigured()).thenReturn(false);

        mvc.perform(post(URL).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("LLM_NOT_CONFIGURED"));

        verify(generator, times(0)).generate(any());
    }

    @Test
    void 요청_값이_잘못되면_400이고_실행_중_상태가_남지_않는다() throws Exception {
        for (String body : List.of("{\"count\":0}", "{\"count\":31}", "{\"count\":-1}", "{}",
                "{\"count\":1,\"categoryId\":99}", "{\"count\":1,\"categoryId\":0}", "not-json")) {
            mvc.perform(post(URL).with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }

        verify(generator, times(0)).generate(any());
        // 400 으로 끝난 요청이 실행 중 표시를 남기면 이후 요청이 409 가 된다.
        start(1, 1);
        awaitFinished();
        assertThat(questions.count()).isEqualTo(1);
    }

    @Test
    void 관리자가_아니면_403이고_인증이_없으면_401이다() throws Exception {
        mvc.perform(post(URL).with(as(userId, "USER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"count\":1}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/status").with(as(userId, "USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get(URL + "/status"))
                .andExpect(status().isUnauthorized());

        verify(generator, times(0)).generate(any());
    }

    @Test
    void 호출마다_생성_요청에_난이도와_개념이_채워진다() throws Exception {
        start(3, 2);
        awaitFinished();

        ArgumentCaptor<QuestionRequest> captor = ArgumentCaptor.forClass(QuestionRequest.class);
        verify(generator, atLeastOnce()).generate(captor.capture());
        assertThat(captor.getAllValues()).extracting(QuestionRequest::difficulty)
                .containsExactlyInAnyOrder(Difficulty.EASY, Difficulty.MEDIUM, Difficulty.HARD);
        assertThat(captor.getAllValues()).allSatisfy(request -> {
            assertThat(request.conceptName()).isEqualTo("프로세스");
            assertThat(request.categoryName()).isEqualTo("운영체제");
        });
    }

    private void start(int count, int categoryId) throws Exception {
        mvc.perform(post(URL).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":" + count + ",\"categoryId\":" + categoryId + "}"))
                .andExpect(status().isAccepted());
    }

    private void awaitFinished() {
        await().atMost(20, TimeUnit.SECONDS).until(() -> "FINISHED".equals(service.status().status()));
    }

    private int countByDifficulty(String difficulty) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM questions WHERE difficulty = ?", Integer.class, difficulty);
    }

    private void existingQuestion(int conceptId, String content) {
        questions.saveAndFlush(Question.create(
                categories.findById(conceptId == 4 ? 2 : 1).orElseThrow(), Difficulty.EASY, content, "해설",
                QuestionSource.MANUAL, concepts.findById(conceptId).orElseThrow(), null,
                List.of(new Question.ChoiceInput("a", true), new Question.ChoiceInput("b", false),
                        new Question.ChoiceInput("c", false), new Question.ChoiceInput("d", false))));
    }

    private static GeneratedQuestion valid() {
        return new GeneratedQuestion(List.of("조건 A를 적용한다", "그 결과에 조건 B를 적용한다"), "문제 본문",
                List.of(new GeneratedQuestion.Choice("정답 보기", true), new GeneratedQuestion.Choice("오답 1", false),
                        new GeneratedQuestion.Choice("오답 2", false), new GeneratedQuestion.Choice("오답 3", false)),
                "정답이 맞는 이유");
    }

    private RequestPostProcessor admin() {
        return as(adminId, "ADMIN");
    }

    private RequestPostProcessor as(Long id, String role) {
        return authentication(new UsernamePasswordAuthenticationToken(
                id, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
