package com.react.auth.controller;

import com.react.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class CsrfController {

    @GetMapping("/csrf")
    public ResponseEntity<ApiResponse<CsrfResponse>> csrf(
            HttpServletRequest request
    ) {
        CsrfToken token = (CsrfToken)
                request.getAttribute(
                        CsrfToken.class.getName()
                );

        if (token == null) {
            throw new IllegalStateException(
                    "CSRF 토큰을 발급할 수 없습니다."
            );
        }

        CsrfResponse response = new CsrfResponse(
                token.getHeaderName(),
                token.getToken()
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(response));
    }

    public record CsrfResponse(
            String headerName,
            String token
    ) {
    }
}