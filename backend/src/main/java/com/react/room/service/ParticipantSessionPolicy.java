package com.react.room.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Base64;

/**
 * SCRUM-128 임시 설정. 세션 유지기간은 팀 정책이 정해지면 환경변수로 변경한다.
 * 최초 입장 시각을 기준으로 하는 고정 만료 방식이다.
 */
@Component
public class ParticipantSessionPolicy {

    private final Duration ttl;
    private final boolean secureCookie;
    private final byte[] joinSecret;

    public ParticipantSessionPolicy(
            @Value("${PARTICIPANT_SESSION_TTL:PT24H}") Duration ttl,
            @Value("${server.servlet.session.cookie.secure:true}") boolean secureCookie,
            @Value("${PARTICIPANT_JOIN_SECRET}") String encodedJoinSecret
    ) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException(
                    "참여자 세션 TTL은 양수여야 합니다."
            );
        }

        byte[] decodedSecret;

        try {
            decodedSecret = Base64.getDecoder()
                    .decode(encodedJoinSecret);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "PARTICIPANT_JOIN_SECRET은 Base64 값이어야 합니다.",
                    e
            );
        }

        if (decodedSecret.length < 32) {
            throw new IllegalArgumentException(
                    "PARTICIPANT_JOIN_SECRET은 최소 32바이트여야 합니다."
            );
        }

        this.ttl = ttl;
        this.secureCookie = secureCookie;
        this.joinSecret = decodedSecret;
    }

    public Duration ttl() {
        return ttl;
    }

    public boolean secureCookie() {
        return secureCookie;
    }

    public byte[] joinSecret() {
        return joinSecret.clone();
    }
}
