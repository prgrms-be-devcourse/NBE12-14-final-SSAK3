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
 * SCRUM-164 HOST 인증 소켓은 청중 쿠키 만료의 영향을
 * 받지 않아야 한다.
 *
 * 청중 인증 TTL은 테스트에서만 8초로 설정한다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.session.cookie.secure=false",
                "PARTICIPANT_SESSION_TTL=PT8S"
        }
)
@ActiveProfiles("test")
class StompHostCookieExpiryIntegrationTest
        extends StompIntegrationTestSupport {

    @Test
    void HOST_소켓은_청중_쿠키가_만료되어도_유지된다()
            throws Exception {

        String email =
                "host-" + UUID.randomUUID() + "@example.com";

        User host = newHost(email);
        LectureRoom room = newRoom(
                host,
                "HOST 쿠키 만료 테스트"
        );

        // HOST 브라우저에도 청중 쿠키가 존재하는 상황
        var joined = participants.join(
                room.getJoinCode(),
                "청중",
                UUID.randomUUID().toString(),
                Map.of()
        );

        String participantCookie =
                "PARTICIPANT_SESSION_"
                        + room.getId()
                        + "="
                        + joined.rawToken();

        Instant participantExpiresAt =
                joined.response()
                        .joinedAt()
                        .plusSeconds(8);

        // HOST_SESSION과 PARTICIPANT_SESSION을 함께 전송한다.
        StompSession session = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                loginCookies(email),
                participantCookie
        );

        try {
            assertThat(session.isConnected()).isTrue();

            // 청중 쿠키가 실제로 유효한 상태에서
            // 소켓을 연결했는지 확인한다.
            assertThat(participantExpiresAt)
                    .isAfter(Instant.now());

            // 청중 쿠키 만료 이후까지 대기한다.
            long remainingMillis = Math.max(
                    0L,
                    Duration.between(
                            Instant.now(),
                            participantExpiresAt.plusMillis(500)
                    ).toMillis()
            );

            Thread.sleep(remainingMillis);

            // 청중 쿠키가 만료되더라도
            // HOST 소켓은 연결 상태를 유지해야 한다.
            assertThat(session.isConnected()).isTrue();

            // 기존 채팅 구독도 계속 가능해야 한다.
            String destination =
                    "/topic/rooms/" + room.getId() + "/chat";

            var received = client.subscribe(
                    session,
                    destination
            );

            assertThat(
                    StompTestClient.awaitBroadcast(
                            received,
                            () -> broker.convertAndSend(
                                    destination,
                                    Map.of("type", "CHAT_CREATED")
                            )
                    )
            ).containsEntry("type", "CHAT_CREATED");

        } finally {
            session.disconnect();
        }
    }
}
