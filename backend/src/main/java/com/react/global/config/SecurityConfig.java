package com.react.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.global.exception.ErrorCode;
import com.react.global.response.ErrorResponse;
import com.react.global.websocket.StompActorResolver;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            ObjectMapper objectMapper
    ) throws Exception {

        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(
                                new HttpSessionCsrfTokenRepository()
                        )
                        .csrfTokenRequestHandler(
                                new CsrfTokenRequestAttributeHandler()
                        )
                )
                .cors(Customizer.withDefaults())
                .securityContext(context -> context
                        .securityContextRepository(
                                securityContextRepository
                        )
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(
                                (request, response, exception) ->
                                        writeError(
                                                response,
                                                ErrorCode.UNAUTHENTICATED,
                                                objectMapper
                                        )
                        )
                        .accessDeniedHandler(
                                (request, response, exception) ->
                                        writeError(
                                                response,
                                                ErrorCode.FORBIDDEN,
                                                objectMapper
                                        )
                        )
                )
                .authorizeHttpRequests(auth -> auth

                        // 회원가입과 로그인은 인증 불필요
                        // 단, POST이므로 CSRF 토큰은 필요
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/auth/signup",
                                "/api/v1/auth/login"
                        ).permitAll()

                        // CSRF 토큰, 공개 강연방, 헬스체크
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/auth/csrf",
                                "/api/v1/public/rooms/**",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info"
                        ).permitAll()

                        // API 문서: 로컬 프로파일에서만 켜지며(springdoc.*.enabled), 꺼져 있으면 404 가 된다.
                        .requestMatchers(
                                HttpMethod.GET,
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**"
                        ).permitAll()

                        // 청중 입장 API: SCRUM-128
                        // HOST 로그인 없이 입장 가능하지만 CSRF 토큰은 필요
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/public/rooms/*/participants"
                        ).permitAll()

                        // SCRUM-128:
                        // 방별 PARTICIPANT_SESSION 쿠키는 Service에서 검증한다.
                        // HOST_SESSION 로그인이 없어도 요청이 Controller에 도달해야 한다.
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/rooms/*/participants/me"
                        ).permitAll()

                        // 청중 투표 응답·내 응답 조회·투표 목록: 방별 PARTICIPANT_SESSION 쿠키는
                        // Controller 에서 검증한다(목록은 HOST 세션도 허용). CSRF 는 그대로 적용한다.
                        .requestMatchers(
                                HttpMethod.PUT,
                                "/api/v1/rooms/*/polls/*/responses/me"
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/rooms/*/polls",
                                "/api/v1/rooms/*/polls/*/responses/me"
                        ).permitAll()

                        // 강연자 인증 API
                        .requestMatchers(
                                "/api/v1/auth/me",
                                "/api/v1/auth/logout"
                        ).hasRole("HOST")

                        // 강연방 생성
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/rooms"
                        ).hasRole("HOST")

                        .requestMatchers(HttpMethod.DELETE, "/api/v1/rooms/*").hasRole("HOST")

                        // 강연자용 강연방 조회
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/rooms/*",
                                "/api/v1/rooms/history/latest",
                                "/api/v1/rooms/history/statuses",
                                "/api/v1/rooms/*/invitation"
                        ).hasRole("HOST")

                        // STT: HOST 역할 필수
                        // 방 소유권은 기존 STT 서비스에서 검증
                        .requestMatchers(
                                "/api/v1/rooms/*/stt/**"
                        ).hasRole("HOST")

                        // 채팅 내역은 요청한 방의 소유자 또는 참여 쿠키를 컨트롤러에서 검증한다.
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/rooms/*/messages"
                        ).permitAll()

                        // SCRUM-131: 연결은 열고, 방별 인증·인가는 STOMP 구독·전송 때 검증한다.
                        // 허용된 Origin 만 연결할 수 있다(WebSocketConfig).
                        .requestMatchers(
                                "/ws",
                                "/ws/**"
                        ).permitAll()

                        .requestMatchers("/error").permitAll()

                        // 지정되지 않은 나머지 API도 인증 필수
                        .anyRequest().authenticated()
                )
                .build();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") String origins
    ) {
        List<String> allowedOrigins =
                Arrays.stream(origins.split(","))
                        .map(String::trim)
                        .filter(origin -> !origin.isBlank())
                        .toList();

        CorsConfiguration configuration =
                new CorsConfiguration();

        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowCredentials(true);

        configuration.setAllowedMethods(
                List.of(
                        "GET",
                        "POST",
                        "PUT",
                        "PATCH",
                        "DELETE",
                        "OPTIONS"
                )
        );

        configuration.setAllowedHeaders(
                List.of(
                        "Content-Type",
                        "X-CSRF-TOKEN",
                        StompActorResolver.CHAT_ROLE_HEADER,
                        "Idempotency-Key",
                        "Accept",
                        "X-Requested-With"
                )
        );

        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration(
                "/api/**",
                configuration
        );

        source.registerCorsConfiguration(
                "/ws",
                configuration
        );

        source.registerCorsConfiguration(
                "/ws/**",
                configuration
        );

        return source;
    }

    private static void writeError(
            HttpServletResponse response,
            ErrorCode errorCode,
            ObjectMapper objectMapper
    ) throws IOException {

        response.setStatus(errorCode.getStatus().value());

        response.setCharacterEncoding(
                StandardCharsets.UTF_8.name()
        );

        response.setContentType(
                MediaType.APPLICATION_JSON_VALUE
        );

        response.setHeader("Cache-Control", "no-store");

        objectMapper.writeValue(
                response.getWriter(),
                ErrorResponse.of(errorCode)
        );
    }
}
