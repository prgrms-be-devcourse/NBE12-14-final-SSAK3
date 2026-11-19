package com.react.global.idempotency;

import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Idempotency-Key 헤더의 형식을 검증한다.
 *
 * 동일한 요청을 재시도할 때 클라이언트는 같은 키를 사용한다.
 * 신규 요청을 시작할 때는 새로운 UUID v4 키를 생성해야 한다.
 *
 * 이 클래스는 키의 형식만 검증한다.
 * 실제 중복 처리 여부는 각 도메인 서비스에서 판단한다.
 */
@Component
public class IdempotencyKeyValidator {

    private static final Pattern UUID_V4_PATTERN =
            Pattern.compile(
                    "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
            );

    /**
     * UUID v4 형식의 멱등성 키인지 확인한다.
     *
     * @param key 요청 헤더의 Idempotency-Key
     * @return 검증된 멱등성 키
     * @throws BusinessException 키가 없거나 형식이 올바르지 않은 경우
     */
    public String validate(String key) {
        if (key == null || !UUID_V4_PATTERN.matcher(key).matches()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        return key;
    }
}
