package com.react.global.idempotency;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * 멱등성 키를 기반으로 동일한 인증 토큰을 생성한다.
 *
 * 동일한 범위(scope), 멱등성 키, 서버 비밀키를 전달하면
 * 항상 동일한 토큰을 반환한다.
 *
 * HMAC-SHA256을 사용하므로 서버 비밀키를 모르면
 * 멱등성 키만으로 토큰을 계산할 수 없다.
 *
 * 이 클래스는 토큰 생성만 담당한다.
 * 참여자 조회와 중복 저장 방지는 각 도메인 서비스에서 처리한다.
 */
@Component
public class IdempotencyTokenGenerator {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * 멱등성 키를 이용해 토큰을 생성한다.
     *
     * @param scope 토큰 사용 범위
     * @param idempotencyKey 클라이언트가 전달한 멱등성 키
     * @param secret 서버에서 관리하는 비밀키
     * @return URL-safe Base64 형식의 토큰
     */
    public String generate(
            String scope,
            String idempotencyKey,
            byte[] secret
    ) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);

            SecretKeySpec keySpec = new SecretKeySpec(
                    secret,
                    HMAC_ALGORITHM
            );

            mac.init(keySpec);

            String input = scope + ":" + idempotencyKey;

            byte[] tokenBytes = mac.doFinal(
                    input.getBytes(StandardCharsets.UTF_8)
            );

            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(tokenBytes);

        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "멱등성 토큰을 생성할 수 없습니다.",
                    e
            );
        }
    }
}
