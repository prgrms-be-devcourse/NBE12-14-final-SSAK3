package com.react.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    @Test
    void 변경_요청용_CSRF_헤더를_문서에_정의한다() {
        OpenAPI openApi = new OpenApiConfig().openApi();

        SecurityScheme csrf = openApi.getComponents().getSecuritySchemes().get("csrf");

        assertThat(openApi.getInfo().getTitle()).isEqualTo("RE:ACT API");
        assertThat(csrf.getName()).isEqualTo("X-CSRF-TOKEN");
        assertThat(csrf.getIn()).isEqualTo(SecurityScheme.In.HEADER);
    }
}
