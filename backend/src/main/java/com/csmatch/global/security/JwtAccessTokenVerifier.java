package com.csmatch.global.security;

import com.csmatch.global.websocket.AccessTokenVerifier;
import com.csmatch.user.entity.User;
import com.csmatch.user.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.OptionalLong;

@Component
@Profile("!test")
@RequiredArgsConstructor
public class JwtAccessTokenVerifier implements AccessTokenVerifier {

    private final JwtTokenService jwtTokenService;
    private final UserRepository userRepository;

    @Override
    public OptionalLong verify(String accessToken) {
        try {
            Long userId = jwtTokenService.verifyAndGetUserId(accessToken);

            return userRepository.findById(userId)
                    .filter(user -> user.getStatus() == User.Status.ACTIVE)
                    .map(user -> OptionalLong.of(user.getUserId()))
                    .orElseGet(OptionalLong::empty);

        } catch (JwtException | IllegalArgumentException exception) {
            return OptionalLong.empty();
        }
    }
}