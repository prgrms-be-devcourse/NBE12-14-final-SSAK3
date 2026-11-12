package com.csmatch.global.config;

import com.csmatch.global.security.JwtAuthenticationFilter;
import com.csmatch.global.security.JwtTokenService;
import com.csmatch.global.security.SecurityErrorHandler;
import com.csmatch.user.repository.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class WebSocketSecurityConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain webSocketSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/ws")
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.GET, "/ws").permitAll()
                        .anyRequest().denyAll())
                .build();
    }

    // 커스텀 체인 추가 시 Boot 기본 보안이 사라지므로 나머지 HTTP 경로 보호를 유지한다.
    // 로그인 구현 시 인증 담당자의 HTTP 보안 체인으로 교체한다.
    @Bean
    @Order(2)
    SecurityFilterChain httpSecurityFilterChain(
            HttpSecurity http,
            JwtTokenService jwtTokenService,
            UserRepository userRepository,
            SecurityErrorHandler errorHandler
    ) throws Exception {

        JwtAuthenticationFilter jwtFilter =
                new JwtAuthenticationFilter(
                        jwtTokenService,
                        userRepository,
                        errorHandler
                );

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/auth/signup",
                                "/api/v1/auth/login"
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.OPTIONS, "/**"
                        ).permitAll()
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/info"
                        ).permitAll()
                        .requestMatchers(
                                "/api/v1/admin/**"
                        ).hasRole("ADMIN")
                        .requestMatchers(
                                "/api/v1/**"
                        ).authenticated()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler)
                )
                .addFilterBefore(
                        jwtFilter,
                        UsernamePasswordAuthenticationFilter.class
                )
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
            String allowedOrigins
    ) {
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowedOrigins(
                Arrays.stream(allowedOrigins.split(","))
                        .map(String::trim)
                        .filter(origin -> !origin.isEmpty())
                        .toList()
        );

        config.setAllowedMethods(
                List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        );

        config.setAllowedHeaders(
                List.of("Authorization", "Content-Type")
        );

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration("/api/**", config);

        return source;
    }
}
