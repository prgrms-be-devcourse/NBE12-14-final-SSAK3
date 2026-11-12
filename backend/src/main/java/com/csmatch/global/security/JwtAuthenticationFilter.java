package com.csmatch.global.security;

import com.csmatch.user.entity.User;
import com.csmatch.user.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenService jwtTokenService;
    private final UserRepository userRepository;
    private final SecurityErrorHandler errorHandler;

    public JwtAuthenticationFilter(
            JwtTokenService jwtTokenService,
            UserRepository userRepository,
            SecurityErrorHandler errorHandler
    ) {
        this.jwtTokenService = jwtTokenService;
        this.userRepository = userRepository;
        this.errorHandler = errorHandler;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }

        String path = request.getServletPath();

        return "/api/v1/auth/signup".equals(path)
                || "/api/v1/auth/login".equals(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String authorization = request.getHeader(
                HttpHeaders.AUTHORIZATION
        );

        if (authorization == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!authorization.regionMatches(
                true, 0, "Bearer ", 0, 7
        )) {
            reject(request, response);
            return;
        }

        try {
            String token = authorization.substring(7).trim();

            Long userId = jwtTokenService.verifyAndGetUserId(token);

            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new BadCredentialsException(
                            "User not found"
                    ));

            if (user.getStatus() != User.Status.ACTIVE) {
                throw new BadCredentialsException("Inactive user");
            }

            String authority = "ROLE_" + user.getRole().name();

            var authentication =
                    new UsernamePasswordAuthenticationToken(
                            user.getUserId(),
                            null,
                            List.of(new SimpleGrantedAuthority(authority))
                    );

            SecurityContextHolder.getContext()
                    .setAuthentication(authentication);

        } catch (JwtException
                 | IllegalArgumentException
                 | AuthenticationException exception) {

            SecurityContextHolder.clearContext();
            reject(request, response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void reject(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        errorHandler.commence(
                request,
                response,
                new BadCredentialsException("Invalid access token")
        );
    }
}