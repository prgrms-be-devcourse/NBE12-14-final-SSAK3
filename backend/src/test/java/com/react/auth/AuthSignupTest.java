package com.react.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthSignupTest {

    private static final String SIGNUP_URL = "/api/v1/auth/signup";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {

        String database = jdbc.sql("SELECT DATABASE()")
                .query(String.class)
                .single();

        assertThat(database).endsWith("_test");

        Flyway.configure()
                .dataSource(dataSource)
                .load()
                .migrate();
    }

    @Test
    void 회원가입_성공시_비밀번호를_해시로_저장한다()
            throws Exception {

        String email = "HOST-" + UUID.randomUUID() + "@example.com";
        String password = "ExamplePassword123!";

        MvcResult result = mvc.perform(post(SIGNUP_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(
                                email,
                                password,
                                "김강연"
                        )))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.userId").isNumber())
                .andExpect(jsonPath("$.data.email")
                        .value(email.toLowerCase(Locale.ROOT)))
                .andExpect(jsonPath("$.data.name").value("김강연"))
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andReturn();

        JsonNode data = objectMapper.readTree(
                result.getResponse().getContentAsString()
        ).get("data");

        long userId = data.get("userId").asLong();

        String storedHash = jdbc.sql(
                        "SELECT password_hash FROM users WHERE id = ?"
                )
                .param(userId)
                .query(String.class)
                .single();

        assertThat(storedHash).isNotEqualTo(password);

        assertThat(
                passwordEncoder.matches(password, storedHash)
        ).isTrue();

        // 회원가입 후 자동 로그인 없음
        assertThat(
                result.getResponse().getCookie("HOST_SESSION")
        ).isNull();
    }

    @Test
    void 같은_이메일로_중복_가입하면_409를_반환한다()
            throws Exception {

        String email =
                "duplicate-" + UUID.randomUUID() + "@example.com";

        mvc.perform(post(SIGNUP_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(
                                email,
                                "Password123!",
                                "첫 사용자"
                        )))
                .andExpect(status().isCreated());

        mvc.perform(post(SIGNUP_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(
                                email.toUpperCase(Locale.ROOT),
                                "Password123!",
                                "두 번째 사용자"
                        )))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    void 잘못된_이메일은_400을_반환한다()
            throws Exception {

        mvc.perform(post(SIGNUP_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(
                                "not-an-email",
                                "Password123!",
                                "김강연"
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_INPUT"));
    }

    @Test
    void 짧은_비밀번호는_400을_반환한다()
            throws Exception {

        mvc.perform(post(SIGNUP_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(
                                "short-" + UUID.randomUUID()
                                        + "@example.com",
                                "123",
                                "김강연"
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_INPUT"));
    }

    @Test
    void 비밀번호가_72바이트를_초과하면_400을_반환한다()
            throws Exception {

        String email =
                "long-" + UUID.randomUUID() + "@example.com";

        // 한글 25자는 UTF-8 기준 75바이트
        String password = "가".repeat(25);

        mvc.perform(post(SIGNUP_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(
                                email,
                                password,
                                "김강연"
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_INPUT"));
    }

    @Test
    void 이름이_비어있으면_400을_반환한다()
            throws Exception {

        String email =
                "blank-" + UUID.randomUUID() + "@example.com";

        mvc.perform(post(SIGNUP_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(
                                email,
                                "Password123!",
                                "   "
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_INPUT"));
    }

    private String request(
            String email,
            String password,
            String name
    ) throws Exception {

        return objectMapper.writeValueAsString(Map.of(
                "email", email,
                "password", password,
                "name", name
        ));
    }
}