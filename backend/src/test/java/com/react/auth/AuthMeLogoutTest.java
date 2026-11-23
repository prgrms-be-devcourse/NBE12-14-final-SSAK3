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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthMeLogoutTest {

    private static final String LOGIN_URL = "/api/v1/auth/login";
    private static final String ME_URL = "/api/v1/auth/me";
    private static final String LOGOUT_URL = "/api/v1/auth/logout";
    private static final String PASSWORD = "ExamplePassword123!";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    private String email;
    private Long userId;

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

        email = "me-" + UUID.randomUUID() + "@example.com";

        User user = userRepository.saveAndFlush(
                new User(
                        email,
                        passwordEncoder.encode(PASSWORD),
                        "김강연"
                )
        );

        userId = user.getId();
    }

    @Test
    void 로그인한_사용자는_자신의_정보를_조회할_수_있다()
            throws Exception {

        MockHttpSession session = login();

        mvc.perform(get(ME_URL).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(userId))
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.name").value("김강연"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    void 로그인하지_않으면_내정보_조회시_401을_반환한다()
            throws Exception {

        mvc.perform(get(ME_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("UNAUTHENTICATED"));
    }

    @Test
    void 로그아웃하면_세션이_무효화되고_쿠키가_만료된다()
            throws Exception {

        MockHttpSession session = login();

        mvc.perform(get(ME_URL).session(session))
                .andExpect(status().isOk());

        MvcResult result = mvc.perform(post(LOGOUT_URL)
                        .with(csrf())
                        .session(session))
                .andExpect(status().isNoContent())
                .andReturn();

        // 서버 세션 무효화 확인
        assertThat(session.isInvalid()).isTrue();

        // 쿠키 만료 헤더 확인
        String setCookie = result.getResponse()
                .getHeader(HttpHeaders.SET_COOKIE);

        assertThat(setCookie).isNotNull();

        assertThat(setCookie)
                .contains("HOST_SESSION=")
                .contains("Path=/")
                .contains("Max-Age=0")
                .contains("HttpOnly")
                .contains("SameSite=Lax");

        // 세션 없이 다시 요청하면 인증 실패
        mvc.perform(get(ME_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("UNAUTHENTICATED"));
    }

    @Test
    void 로그인하지_않고_로그아웃하면_401을_반환한다()
            throws Exception {

        mvc.perform(post(LOGOUT_URL)
                        .with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("UNAUTHENTICATED"));
    }

    private MockHttpSession login() throws Exception {

        String body = objectMapper.writeValueAsString(Map.of(
                "email", email,
                "password", PASSWORD
        ));

        MvcResult result = mvc.perform(post(LOGIN_URL)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession)
                result.getRequest().getSession(false);

        assertThat(session).isNotNull();

        return session;
    }
}