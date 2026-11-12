package com.csmatch.auth;

import com.csmatch.global.security.JwtAccessTokenVerifier;
import com.csmatch.global.security.JwtTokenService;
import com.csmatch.user.entity.User;
import com.csmatch.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JwtAccessTokenVerifierTest {

    private final JwtTokenService jwtTokenService =
            new JwtTokenService("0123456789abcdef0123456789abcdef");

    private final UserRepository userRepository =
            mock(UserRepository.class);

    private final JwtAccessTokenVerifier verifier =
            new JwtAccessTokenVerifier(jwtTokenService, userRepository);

    @Test
    void 유효한_JWT와_활성_회원이면_userId를_반환한다() {

        User user = User.create(
                "test@example.com",
                "test-hash",
                "테스터"
        );

        ReflectionTestUtils.setField(user, "userId", 7L);

        when(userRepository.findById(7L))
                .thenReturn(Optional.of(user));

        String token = jwtTokenService.issue(7L);

        var result = verifier.verify(token);

        assertThat(result.isPresent()).isTrue();
        assertThat(result.getAsLong()).isEqualTo(7L);
    }

    @Test
    void 잘못된_JWT는_거부한다() {

        var result = verifier.verify("invalid.token");

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void 탈퇴한_회원의_JWT는_거부한다() {

        User user = User.create(
                "test@example.com",
                "test-hash",
                "테스터"
        );

        ReflectionTestUtils.setField(user, "userId", 7L);
        ReflectionTestUtils.setField(
                user,
                "status",
                User.Status.WITHDRAWN
        );

        when(userRepository.findById(7L))
                .thenReturn(Optional.of(user));

        String token = jwtTokenService.issue(7L);

        var result = verifier.verify(token);

        assertThat(result.isEmpty()).isTrue();
    }
}