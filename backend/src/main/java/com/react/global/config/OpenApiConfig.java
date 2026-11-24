package com.react.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    private static final String CSRF_SCHEME = "csrf";

    // 세션 쿠키는 브라우저가 알아서 보낸다. POST·PUT·DELETE 는 GET /api/v1/auth/csrf 로 받은 토큰을 직접 넣어야 한다.
    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("RE:ACT API")
                        .description("청중 반응 실시간 소통 서비스 REST API. 변경 요청은 X-CSRF-TOKEN 헤더가 필요하다.")
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes(CSRF_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-CSRF-TOKEN")))
                .addSecurityItem(new SecurityRequirement().addList(CSRF_SCHEME));
    }
}
