package com.react.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.auth.entity.User;
import com.react.auth.repository.UserRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthLoginTest {

    private static final String LOGIN_URL = "/api/v1/auth/login";
    private static final String PASSWORD = "ExamplePassword123!";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;

    private String email;

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

        email = "login-" + UUID.randomUUID() + "@example.com";

        userRepository.saveAndFlush(
                new User(
                        email,
                        passwordEncoder.encode(PASSWORD),
                        "김강연"
                )
        );
    }

    @Test
    void 로그인_성공시_인증정보가_세션에_저장된다()
            throws Exception {

        var result = mvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(
                                email.toUpperCase(Locale.ROOT),
                                PASSWORD
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").isNumber())
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.name").value("김강연"))
                .andExpect(jsonPath("$.data.authenticated").value(true))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andReturn();

        MockHttpSession session = (MockHttpSession)
                result.getRequest().getSession(false);

        assertThat(session).isNotNull();

        SecurityContext context = (SecurityContext)
                session.getAttribute(
                        HttpSessionSecurityContextRepository
                                .SPRING_SECURITY_CONTEXT_KEY
                );

        assertThat(context).isNotNull();
        assertThat(context.getAuthentication().getName())
                .isEqualTo(email);

        assertThat(context.getAuthentication()
                .getAuthorities())
                .extracting("authority")
                .contains("ROLE_HOST");
    }

    @Test
    void 비밀번호가_틀리면_401을_반환하고_세션을_발급하지_않는다()
            throws Exception {

        var result = mvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(email, "WrongPassword123!")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_CREDENTIALS"))
                .andReturn();

        assertThat(result.getRequest().getSession(false))
                .isNull();
    }

    @Test
    void 존재하지_않는_이메일이면_401을_반환한다()
            throws Exception {

        mvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(
                                "missing-" + UUID.randomUUID()
                                        + "@example.com",
                                PASSWORD
                        )))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_CREDENTIALS"));
    }

    @Test
    void 기존_세션으로_로그인하면_세션_ID가_변경된다()
            throws Exception {

        MockHttpSession session = new MockHttpSession();
        String previousId = session.getId();

        mvc.perform(post(LOGIN_URL)
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(email, PASSWORD)))
                .andExpect(status().isOk());

        assertThat(session.getId()).isNotEqualTo(previousId);
    }

    private String request(String email, String password)
            throws Exception {

        return objectMapper.writeValueAsString(Map.of(
                "email", email,
                "password", password
        ));
    }

    @Test
    void 로그인_후_다음_요청에서도_인증이_유지된다() throws Exception {

        var result = mvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession)
                result.getRequest().getSession(false);

        assertThat(session).isNotNull();

        mvc.perform(get("/actuator/health").session(session))
                .andExpect(status().isOk())
                .andExpect(authenticated().withUsername(email));
    }
}
