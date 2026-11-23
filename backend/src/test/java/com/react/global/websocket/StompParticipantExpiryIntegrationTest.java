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
 * SCRUM-164 청중 인증 TTL 만료 통합 테스트.
 *
 * 테스트에서만 청중 인증 유효기간을 8초로 설정한다.
 * 별도의 SEND 요청 없이도 연결이 종료되어야 한다.
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

        String email = "host-" + UUID.randomUUID() + "@example.com";

        User host = newHost(email);
        LectureRoom room = newRoom(host, "청중 만료 테스트");

        // 정상적인 청중 참여 세션을 생성한다.
        var joined = participants.join(
                room.getJoinCode(),
                "청중A",
                UUID.randomUUID().toString(),
                Map.of()
        );

        String cookie =
                "PARTICIPANT_SESSION_"
                        + room.getId()
                        + "="
                        + joined.rawToken();

        StompSession session = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                cookie
        );

        try {
            assertThat(session.isConnected()).isTrue();

            // 청중 인증의 만료 시각을 계산한다.
            Instant expiresAt = joined.response()
                    .joinedAt()
                    .plus(PARTICIPANT_TTL);

            long remainingMillis = Math.max(
                    0L,
                    Duration.between(
                            Instant.now(),
                            expiresAt
                    ).toMillis()
            );

            // 만료 시각 직후까지 기다린다.
            Thread.sleep(remainingMillis + 400);

            // 추가 SEND 없이 WebSocket 자체가 종료되어야 한다.
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