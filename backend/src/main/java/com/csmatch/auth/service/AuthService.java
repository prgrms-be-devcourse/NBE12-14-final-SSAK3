package com.csmatch.auth.service;

import com.csmatch.auth.dto.request.LoginRequest;
import com.csmatch.auth.dto.request.SignupRequest;
import com.csmatch.auth.dto.response.LoginResponse;
import com.csmatch.auth.dto.response.SignupResponse;
import com.csmatch.global.exception.BusinessException;
import com.csmatch.global.exception.ErrorCode;
import com.csmatch.global.security.JwtTokenService;
import com.csmatch.user.entity.User;
import com.csmatch.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;

    @Transactional
    public SignupResponse signup(SignupRequest request) {

        String email = request.email()
                .trim()
                .toLowerCase(Locale.ROOT);

        String nickname = request.nickname().trim();

        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.EMAIL_DUPLICATED);
        }

        if (userRepository.existsByNickname(nickname)) {
            throw new BusinessException(ErrorCode.NICKNAME_DUPLICATED);
        }

        if (request.password()
                .getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        String passwordHash = passwordEncoder.encode(
                request.password()
        );

        User user = User.create(email, passwordHash, nickname);

        User savedUser = userRepository.saveAndFlush(user);

        return new SignupResponse(
                savedUser.getUserId(),
                savedUser.getNickname(),
                savedUser.getRating()
        );
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {

        String email = request.email()
                .trim()
                .toLowerCase(Locale.ROOT);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INVALID_CREDENTIALS
                ));

        if (user.getStatus() != User.Status.ACTIVE
                || user.getPasswordHash() == null
                || !passwordEncoder.matches(
                request.password(),
                user.getPasswordHash()
        )) {
            throw new BusinessException(
                    ErrorCode.INVALID_CREDENTIALS
            );
        }

        String accessToken = jwtTokenService.issue(
                user.getUserId()
        );

        return new LoginResponse(
                accessToken,
                JwtTokenService.EXPIRES_IN
        );
    }
}