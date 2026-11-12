package com.csmatch.global.websocket;

import java.util.OptionalLong;

/** 인증 모듈이 구현한다. 유효한 액세스 토큰일 때만 검증된 사용자 ID를 반환한다. */
@FunctionalInterface
public interface AccessTokenVerifier {
    OptionalLong verify(String accessToken);
}
