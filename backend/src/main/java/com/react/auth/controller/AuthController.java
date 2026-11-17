package com.react.auth.controller;

import com.react.auth.dto.LoginRequest;
import com.react.auth.dto.LoginResponse;
import com.react.auth.dto.SignupRequest;
import com.react.auth.dto.SignupResponse;
import com.react.auth.service.AuthService;
import com.react.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final SecurityContextRepository securityContextRepository;

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

        // 기존 세션이 있다면 로그인 시 세션 ID 변경
        if (servletRequest.getSession(false) != null) {
            servletRequest.changeSessionId();
        }

        // STT에서도 이메일로 사용자를 식별하도록 설정
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        response.email(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_HOST"))
                );

        SecurityContext context =
                SecurityContextHolder.createEmptyContext();

        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        // 다음 HTTP 요청에서도 인증 상태를 사용할 수 있도록 저장
        securityContextRepository.saveContext(
                context,
                servletRequest,
                servletResponse
        );

        return ApiResponse.of(response);
    }
}