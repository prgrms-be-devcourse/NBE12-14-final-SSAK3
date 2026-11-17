package com.react.auth.controller;

import com.react.auth.dto.LoginRequest;
import com.react.auth.dto.LoginResponse;
import com.react.auth.dto.MeResponse;
import com.react.auth.dto.SignupRequest;
import com.react.auth.dto.SignupResponse;
import com.react.auth.service.AuthService;
import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.server.Cookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final SecurityContextRepository securityContextRepository;
    private final ServerProperties serverProperties;

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<SignupResponse>> signup(
            @Valid @RequestBody SignupRequest request
    ) {
        SignupResponse response = authService.signup(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.of(response));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        LoginResponse response = authService.login(request);

        // 기존 세션이 있으면 로그인 시 세션 ID 변경
        if (servletRequest.getSession(false) != null) {
            servletRequest.changeSessionId();
        }

        // 이메일을 인증 주체로 사용
        Authentication authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        response.email(),
                        null,
                        List.of(
                                new SimpleGrantedAuthority("ROLE_HOST")
                        )
                );

        SecurityContext context =
                SecurityContextHolder.createEmptyContext();

        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        // 다음 요청에서도 인증 정보 유지
        securityContextRepository.saveContext(
                context,
                servletRequest,
                servletResponse
        );

        return ApiResponse.of(response);
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(
            Authentication authentication
    ) {
        String email = requireHostEmail(authentication);

        MeResponse response = authService.me(email);

        return ApiResponse.of(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            Authentication authentication,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        // 인증된 강연자인지 확인
        requireHostEmail(authentication);

        // 서버 세션 무효화
        HttpSession session = servletRequest.getSession(false);

        if (session != null) {
            session.invalidate();
        }

        SecurityContextHolder.clearContext();

        // application.yml의 세션 쿠키 설정 사용
        Cookie cookie = serverProperties.getServlet()
                .getSession()
                .getCookie();

        String cookiePath = cookie.getPath() != null
                ? cookie.getPath()
                : "/";

        ResponseCookie.ResponseCookieBuilder cookieBuilder =
                ResponseCookie.from(cookie.getName(), "")
                        .path(cookiePath)
                        .httpOnly(Boolean.TRUE.equals(cookie.getHttpOnly()))
                        .secure(Boolean.TRUE.equals(cookie.getSecure()))
                        .maxAge(Duration.ZERO);

        if (cookie.getSameSite() != null) {
            cookieBuilder.sameSite(
                    cookie.getSameSite().attributeValue()
            );
        }

        if (cookie.getDomain() != null) {
            cookieBuilder.domain(cookie.getDomain());
        }

        ResponseCookie expiredCookie = cookieBuilder.build();

        servletResponse.addHeader(
                HttpHeaders.SET_COOKIE,
                expiredCookie.toString()
        );

        return ResponseEntity.noContent().build();
    }

    private String requireHostEmail(
            Authentication authentication
    ) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {

            throw new BusinessException(
                    ErrorCode.UNAUTHENTICATED
            );
        }

        boolean isHost = authentication.getAuthorities()
                .stream()
                .anyMatch(authority ->
                        "ROLE_HOST".equals(authority.getAuthority())
                );

        if (!isHost) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        return authentication.getName();
    }
}