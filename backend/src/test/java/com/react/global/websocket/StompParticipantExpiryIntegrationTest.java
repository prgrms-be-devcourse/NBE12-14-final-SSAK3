package com.react.global.websocket;

import com.react.auth.entity.User;
import com.react.room.entity.LectureRoom;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-164 청중 인증의 실제 만료를 검증한다.
 *
 * 청중의 인증 유효기간을 테스트에서만 8초로 줄인다.
 * WebSocket 연결 후 별도의 SEND 요청을 하지 않아도
 * 인증 만료 시 기존 연결이 종료되어야 한다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.session.cookie.secure=false",
                "server.servlet.session.timeout=30m",
                "PARTICIPANT_SESSION_TTL=PT8S"
        }
)
@ActiveProfiles("test")
class StompParticipantExpiryIntegrationTest
        extends StompIntegrationTestSupport {

    private static final Duration PARTICIPANT_TTL =
            Duration.ofSeconds(8);

    @Test
    void 청중_인증이_만료되면_기존_WebSocket이_종료된다()
            throws Exception {

        // 1. 테스트용 강연자와 강연방 생성
        String email =
                "host-" + UUID.randomUUID() + "@example.com";

        User host = newHost(email);
        LectureRoom room = newRoom(host, "청중 만료 테스트");

        // 2. 청중이 강연방에 정상 입장한다.
        var joined = participants.join(
                room.getJoinCode(),
                "청중A",
                UUID.randomUUID().toString(),
                Map.of()
        );

        // 3. 방별 청중 쿠키를 준비한다.
        String cookie =
                "PARTICIPANT_SESSION_"
                        + room.getId()
                        + "="
                        + joined.rawToken();

        // 4. 유효한 청중 쿠키로 실제 WebSocket 연결
        StompSession session = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                cookie
        );

        try {
            assertThat(session.isConnected()).isTrue();

            // 청중 인증은 최초 입장 시각을 기준으로 만료된다.
            Instant expiresAt = joined.response()
                    .joinedAt()
                    .plus(PARTICIPANT_TTL);

            // 만료 시각 직후까지 기다린다.
            // 현재 시간에서 남은 시간만 기다리므로
            // 전체 테스트 실행 시간에 덜 영향을 받는다.
            long remainingMillis = Math.max(
                    0L,
                    Duration.between(
                            Instant.now(),
                            expiresAt
                    ).toMillis()
            );

            Thread.sleep(remainingMillis + 400);

            // 5. 별도의 SEND 요청 없이도
            // WebSocket 연결 자체가 종료되어야 한다.
            assertThat(
                    StompTestClient.awaitClosed(session)
            ).isTrue();

        } finally {
            if (session.isConnected()) {
                session.disconnect();
            }
        }
    }
}
