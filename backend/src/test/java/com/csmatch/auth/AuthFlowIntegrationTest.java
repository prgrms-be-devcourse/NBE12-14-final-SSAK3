package com.csmatch.auth;

import com.csmatch.global.response.ApiResponse;
import com.csmatch.user.entity.User;
import com.csmatch.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import(AuthFlowIntegrationTest.TestController.class)
class AuthFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    void 회원가입하면_비밀번호가_암호화되어_저장된다() throws Exception {

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.userId").isNumber())
                .andExpect(jsonPath("$.data.nickname").value("테스터"))
                .andExpect(jsonPath("$.data.rating").value(1000));

        User user = userRepository.findByEmail(
                "test@example.com"
        ).orElseThrow();

        assertThat(user.getPasswordHash())
                .isNotEqualTo("password123");

        assertThat(passwordEncoder.matches(
                "password123",
                user.getPasswordHash()
        )).isTrue();

        assertThat(user.getRole()).isEqualTo(User.Role.USER);
        assertThat(user.getStatus()).isEqualTo(User.Status.ACTIVE);
    }

    @Test
    void 이메일_중복이면_409를_반환한다() throws Exception {

        signup();

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("EMAIL_DUPLICATED"));
    }

    @Test
    void 닉네임_중복이면_409를_반환한다() throws Exception {

        signup();

        String request = """
                {
                  "email": "another@example.com",
                  "password": "password123",
                  "nickname": "테스터"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("NICKNAME_DUPLICATED"));
    }

    @Test
    void 회원가입_입력값이_잘못되면_400을_반환한다() throws Exception {

        String request = """
                {
                  "email": "invalid-email",
                  "password": "123",
                  "nickname": ""
                }
                """;

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("VALIDATION_FAILED"));
    }

    @Test
    void 로그인하면_JWT를_발급한다() throws Exception {

        signup();

        String token = loginAndGetToken();

        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    void 잘못된_비밀번호로는_로그인할_수_없다() throws Exception {

        signup();

        String request = """
                {
                  "email": "test@example.com",
                  "password": "wrongpassword"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_CREDENTIALS"));
    }

    @Test
    void 탈퇴한_사용자는_로그인할_수_없다() throws Exception {

        signup();

        jdbcTemplate.update(
                "UPDATE users SET status = 'WITHDRAWN' WHERE email = ?",
                "test@example.com"
        );

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_CREDENTIALS"));
    }

    @Test
    void JWT가_없으면_보호된_API는_401을_반환한다() throws Exception {

        mockMvc.perform(get("/api/v1/auth-test/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code")
                        .value("AUTH_REQUIRED"));
    }

    @Test
    void 정상_JWT로_로그인한_사용자_ID를_조회한다() throws Exception {

        signup();

        String token = loginAndGetToken();

        Long expectedUserId = userRepository.findByEmail(
                "test@example.com"
        ).orElseThrow().getUserId();

        String response = mockMvc.perform(
                        get("/api/v1/auth-test/me")
                                .header("Authorization", "Bearer " + token)
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        long actualUserId = objectMapper.readTree(response)
                .path("data")
                .asLong();

        assertThat(actualUserId).isEqualTo(expectedUserId);
    }

    @Test
    void 잘못된_JWT는_401을_반환한다() throws Exception {

        mockMvc.perform(get("/api/v1/auth-test/me")
                        .header("Authorization", "Bearer invalid.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code")
                        .value("AUTH_REQUIRED"));
    }

    @Test
    void 일반_사용자는_관리자_API에_접근할_수_없다() throws Exception {

        signup();

        String token = loginAndGetToken();

        mockMvc.perform(get("/api/v1/admin/auth-test")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code")
                        .value("FORBIDDEN"));
    }

    @Test
    void 관리자는_관리자_API에_접근할_수_있다() throws Exception {

        signup();

        jdbcTemplate.update(
                "UPDATE users SET role = 'ADMIN' WHERE email = ?",
                "test@example.com"
        );

        String token = loginAndGetToken();

        mockMvc.perform(get("/api/v1/admin/auth-test")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("admin-ok"));
    }

    private void signup() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupJson()))
                .andExpect(status().isCreated());
    }

    private String loginAndGetToken() throws Exception {

        String response = mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginJson())
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.expiresIn").value(3600))
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response)
                .path("data")
                .path("accessToken")
                .asText();
    }

    private String signupJson() {
        return """
                {
                  "email": "test@example.com",
                  "password": "password123",
                  "nickname": "테스터"
                }
                """;
    }

    private String loginJson() {
        return """
                {
                  "email": "test@example.com",
                  "password": "password123"
                }
                """;
    }

    @RestController
    public static class TestController {

        @GetMapping("/api/v1/auth-test/me")
        public ApiResponse<Long> myUserId(
                @AuthenticationPrincipal Long userId
        ) {
            return ApiResponse.of(userId);
        }

        @GetMapping("/api/v1/admin/auth-test")
        public ApiResponse<String> admin() {
            return ApiResponse.of("admin-ok");
        }
    }
}
