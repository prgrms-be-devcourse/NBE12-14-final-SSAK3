package com.csmatch.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

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
    SecurityFilterChain httpSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .formLogin(Customizer.withDefaults())
                .build();
    }
}
