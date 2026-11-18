package com.react.auth.service;

import com.react.auth.dto.LoginRequest;
import com.react.auth.dto.LoginResponse;
import com.react.auth.dto.MeResponse;
import com.react.auth.dto.SignupRequest;
import com.react.auth.dto.SignupResponse;
import com.react.auth.entity.User;
import com.react.auth.repository.UserRepository;
import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public SignupResponse signup(SignupRequest request) {

        String email = normalizeEmail(request.email());
        String name = request.name().strip();

        // BCrypt 입력 길이 검사
        if (request.password()
                .getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        // 저장 전 이메일 중복 검사
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        // 비밀번호 해시 처리
        String encodedPassword =
                passwordEncoder.encode(request.password());

        User user = new User(email, encodedPassword, name);

        // DB UNIQUE 제약으로 동시 가입 요청 보호
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {

            if (isEmailDuplicate(e)) {
                throw new BusinessException(
                        ErrorCode.EMAIL_ALREADY_EXISTS
                );
            }

            throw e;
        }

        return new SignupResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getCreatedAt()
                        .atZone(SEOUL)
                        .toOffsetDateTime()
        );
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {

        String email = normalizeEmail(request.email());

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INVALID_CREDENTIALS
                ));

        if (!passwordEncoder.matches(
                request.password(),
                user.getPasswordHash()
        )) {
            throw new BusinessException(
                    ErrorCode.INVALID_CREDENTIALS
            );
        }

        return new LoginResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                true
        );
    }

    @Transactional(readOnly = true)
    public MeResponse me(String email) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.UNAUTHENTICATED
                ));

        return new MeResponse(
                user.getId(),
                user.getEmail(),
                user.getName()
        );
    }

    private String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private boolean isEmailDuplicate(
            DataIntegrityViolationException exception
    ) {
        Throwable cause = exception;

        while (cause != null) {

            if (cause instanceof SQLException sqlException
                    && sqlException.getErrorCode() == 1062
                    && sqlException.getMessage() != null
                    && sqlException.getMessage()
                    .contains("uk_users_email")) {

                return true;
            }

            cause = cause.getCause();
        }

        return false;
    }
}