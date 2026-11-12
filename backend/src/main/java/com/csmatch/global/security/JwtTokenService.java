package com.csmatch.global.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtTokenService {

    public static final long EXPIRES_IN = 3600L;

    private final SecretKey secretKey;

    public JwtTokenService(
            @Value("${app.jwt.secret}") String secret
    ) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);

        if (keyBytes.length < 32) {
            throw new IllegalArgumentException(
                    "JWT secret must contain at least 32 bytes"
            );
        }

        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String issue(Long userId) {
        Instant now = Instant.now();

        return Jwts.builder()
                .issuer("csmatch")
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())
                .claim("type", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(EXPIRES_IN)))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    public Long verifyAndGetUserId(String token) {
        if (token == null || token.isBlank() || token.length() > 8192) {
            throw new JwtException("Invalid access token");
        }

        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        if (!"csmatch".equals(claims.getIssuer())
                || !"access".equals(claims.get("type", String.class))
                || claims.getSubject() == null
                || claims.getExpiration() == null) {
            throw new JwtException("Invalid access token claims");
        }

        Long userId = Long.parseLong(claims.getSubject());

        if (userId <= 0) {
            throw new JwtException("Invalid user ID");
        }

        return userId;
    }
}