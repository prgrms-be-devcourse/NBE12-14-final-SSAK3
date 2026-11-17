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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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

    @Value("${server.servlet.session.cookie.secure:true}")
    private boolean sessionCookieSecure;

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

        // 강연자의 이메일을 인증 주체로 사용
        var authentication =
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

        // 다음 요청에서도 인증 상태를 복원할 수 있도록 저장
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

        return ApiResponse.of(authService.me(email));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            Authentication authentication,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {

        // 로그인한 강연자인지 확인
        requireHostEmail(authentication);

        // 서버 세션 무효화
        HttpSession session = servletRequest.getSession(false);

        if (session != null) {
            session.invalidate();
        }

        // 현재 요청의 SecurityContext 초기화
        SecurityContextHolder.clearContext();

        // 브라우저의 HOST_SESSION 쿠키 만료
        ResponseCookie expiredCookie = ResponseCookie
                .from("HOST_SESSION", "")
                .httpOnly(true)
                .secure(sessionCookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();

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