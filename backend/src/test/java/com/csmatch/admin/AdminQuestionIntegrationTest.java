package com.csmatch.admin;

import com.csmatch.question.Enum.QuestionSource;
import com.csmatch.question.Enum.QuestionStatus;
import com.csmatch.question.entity.Question;
import com.csmatch.question.repository.QuestionRepository;
import com.csmatch.user.entity.User;
import com.csmatch.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AdminQuestionIntegrationTest {
    private static final String URL = "/api/v1/admin/questions";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired QuestionRepository questions;

    Long adminId;
    Long userId;

    @BeforeEach
    void setUp() {
        // Question.createdAt 은 DB 기본값에 의존하므로 schema 의 기본값을 테스트 DDL 에도 맞춘다.
        jdbc.execute("ALTER TABLE questions MODIFY created_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP");
        for (String table : List.of("question_choices", "questions", "concepts", "categories", "users")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("INSERT INTO categories(category_id, name) VALUES (1, '네트워크')");
        adminId = users.saveAndFlush(User.create("admin@example.com", "test-hash", "관리자")).getUserId();
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE user_id = ?", adminId);
        userId = users.saveAndFlush(User.create("user@example.com", "test-hash", "회원")).getUserId();
    }

    @Test
    void 관리자가_등록하면_DRAFT_문제와_보기_4개가_저장된다() throws Exception {
        mvc.perform(post(URL).with(as(adminId, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1, "EASY", "[1,2,3,4]", 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.questionId").isNumber())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        Question saved = questions.findAll().getFirst();
        assertThat(saved.getStatus()).isEqualTo(QuestionStatus.DRAFT);
        assertThat(saved.getSource()).isEqualTo(QuestionSource.MANUAL);
        assertThat(saved.getCreatedBy()).isEqualTo(adminId);
        assertThat(saved.getConcept()).isNull();
        assertThat(saved.getExplanation()).isEqualTo("해설");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_choices WHERE question_id = ?",
                Integer.class, saved.getId())).isEqualTo(4);
    }

    @Test
    void 보기를_순서와_다르게_보내도_choiceNo_기준으로_저장하고_정답이_유지된다() throws Exception {
        mvc.perform(post(URL).with(as(adminId, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1, "HARD", "[3,1,4,2]", 3)))
                .andExpect(status().isCreated());

        List<Map<String, Object>> choices = jdbc.queryForList(
                "SELECT choice_no, content, is_correct FROM question_choices ORDER BY choice_id");
        assertThat(choices).extracting(row -> ((Number) row.get("choice_no")).intValue())
                .containsExactly(1, 2, 3, 4);
        assertThat(choices).extracting(row -> row.get("content"))
                .containsExactly("보기1", "보기2", "보기3", "보기4");
        assertThat(choices.stream().filter(row -> (Boolean) row.get("is_correct")))
                .singleElement().extracting(row -> ((Number) row.get("choice_no")).intValue()).isEqualTo(3);
    }

    @Test
    void 보기가_4개가_아니면_400이다() throws Exception {
        expectInvalid(body(1, "EASY", "[1,2,3]", 1));
        expectInvalid(body(1, "EASY", "[1,2,3,4,5]", 1));
    }

    @Test
    void 정답이_정확히_1개가_아니면_400이다() throws Exception {
        expectInvalid(body(1, "EASY", "[1,2,3,4]", 0));
        expectInvalid("""
                {"categoryId":1,"difficulty":"EASY","content":"문제","explanation":"해설","choices":[
                {"choiceNo":1,"content":"a","isCorrect":true},{"choiceNo":2,"content":"b","isCorrect":true},
                {"choiceNo":3,"content":"c","isCorrect":false},{"choiceNo":4,"content":"d","isCorrect":false}]}""");
    }

    @Test
    void 보기_번호가_1에서_4까지_한_번씩이_아니면_400이다() throws Exception {
        expectInvalid(body(1, "EASY", "[1,1,2,3]", 1));
        expectInvalid(body(1, "EASY", "[1,2,3,5]", 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"categoryId\":1,\"difficulty\":\"EASY\",\"content\":\"\",\"explanation\":\"해설\",\"choices\":[]}",
            "not-json"
    })
    void 필수_값이_없거나_형식이_잘못되면_400이다(String json) throws Exception {
        expectInvalid(json);
    }

    @Test
    void 필수_필드가_비어_있으면_400이다() throws Exception {
        expectInvalid(body(1, "EASY", "[1,2,3,4]", 1).replace("\"content\":\"문제\"", "\"content\":\" \""));
        expectInvalid(body(1, "EASY", "[1,2,3,4]", 1).replace("\"explanation\":\"해설\"", "\"explanation\":\"\""));
    }

    @Test
    void 없는_분야이거나_허용되지_않는_난이도면_400이다() throws Exception {
        expectInvalid(body(99, "EASY", "[1,2,3,4]", 1));
        expectInvalid(body(1, "쉬움", "[1,2,3,4]", 1));
    }

    @Test
    void 일반_회원은_403이고_인증이_없으면_401이며_저장되지_않는다() throws Exception {
        mvc.perform(post(URL).with(as(userId, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1, "EASY", "[1,2,3,4]", 1)))
                .andExpect(status().isForbidden());
        mvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1, "EASY", "[1,2,3,4]", 1)))
                .andExpect(status().isUnauthorized());

        assertThat(questions.count()).isZero();
    }

    @Test
    void 검증에_실패하면_문제가_저장되지_않는다() throws Exception {
        expectInvalid(body(1, "EASY", "[1,2,3,4]", 0));

        assertThat(questions.count()).isZero();
    }

    private void expectInvalid(String json) throws Exception {
        mvc.perform(post(URL).with(as(adminId, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    private RequestPostProcessor as(Long id, String role) {
        return authentication(new UsernamePasswordAuthenticationToken(
                id, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    /** order 의 순서대로 보기를 만든다. correctNo 와 같은 choiceNo 가 정답이고, 0이면 정답이 없다. */
    private String body(int categoryId, String difficulty, String order, int correctNo) {
        String choices = order.substring(1, order.length() - 1);
        StringBuilder json = new StringBuilder();
        for (String no : choices.split(",")) {
            if (!json.isEmpty()) {
                json.append(',');
            }
            json.append("{\"choiceNo\":%s,\"content\":\"보기%s\",\"isCorrect\":%s}"
                    .formatted(no, no, Integer.parseInt(no) == correctNo));
        }
        return """
                {"categoryId":%d,"difficulty":"%s","content":"문제","explanation":"해설","choices":[%s]}"""
                .formatted(categoryId, difficulty, json);
    }
}
