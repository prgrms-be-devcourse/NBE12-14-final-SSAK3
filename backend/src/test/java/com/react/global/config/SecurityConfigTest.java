package com.react.global.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void 미로그인_사용자는_보호된_API에_접근할_수_없다()
            throws Exception {

        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("UNAUTHENTICATED"));

        mvc.perform(get("/api/v1/rooms/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("UNAUTHENTICATED"));
    }

    @Test
    void 강연자가_아닌_사용자는_HOST_API에_접근할_수_없다()
            throws Exception {

        mvc.perform(get("/api/v1/auth/me")
                        .with(user("participant")
                                .roles("PARTICIPANT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("FORBIDDEN"));
    }

    @Test
    void 헬스체크는_로그인하지_않아도_접근할_수_있다()
            throws Exception {

        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void CSRF_토큰이_없으면_POST_요청을_거부한다()
            throws Exception {

        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("FORBIDDEN"));
    }

    @Test
    void CSRF_토큰을_발급받을_수_있다()
            throws Exception {

        MvcResult result = mvc.perform(
                        get("/api/v1/auth/csrf")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.headerName")
                        .value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.data.token")
                        .isString())
                .andReturn();

        JsonNode data = objectMapper.readTree(
                result.getResponse().getContentAsString()
        ).get("data");

        assertThat(data.get("token").asText())
                .isNotBlank();

        assertThat(
                result.getRequest().getSession(false)
        ).isNotNull();
    }

    @Test
    void 유효한_CSRF_토큰으로_요청하면_입력값_검증까지_진행한다()
            throws Exception {

        MvcResult bootstrap = mvc.perform(
                        get("/api/v1/auth/csrf")
                )
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession)
                bootstrap.getRequest().getSession(false);

        assertThat(session).isNotNull();

        JsonNode data = objectMapper.readTree(
                bootstrap.getResponse().getContentAsString()
        ).get("data");

        mvc.perform(post("/api/v1/auth/signup")
                        .session(session)
                        .header(
                                data.get("headerName").asText(),
                                data.get("token").asText()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_INPUT"));
    }

    @Test
    void 유효한_CSRF가_있어도_로그인이_없으면_401이다()
            throws Exception {

        MvcResult bootstrap = mvc.perform(
                        get("/api/v1/auth/csrf")
                )
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession)
                bootstrap.getRequest().getSession(false);

        assertThat(session).isNotNull();

        JsonNode data = objectMapper.readTree(
                bootstrap.getResponse().getContentAsString()
        ).get("data");

        mvc.perform(post("/api/v1/rooms/123/stt/sessions")
                        .session(session)
                        .header(
                                "X-CSRF-TOKEN",
                                data.get("token").asText()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("UNAUTHENTICATED"));
    }

    @Test
    void 권한_구현_전까지_채팅_조회와_WebSocket을_차단한다()
            throws Exception {

        mvc.perform(get("/api/v1/rooms/1/messages")
                        .with(user("host@example.com")
                                .roles("HOST")))
                .andExpect(status().isForbidden());

        mvc.perform(get("/ws")
                        .with(user("host@example.com")
                                .roles("HOST")))
                .andExpect(status().isForbidden());
    }
}