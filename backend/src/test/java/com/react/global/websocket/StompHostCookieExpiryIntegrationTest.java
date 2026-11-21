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
 * SCRUM-164 HOST와 PARTICIPANT 쿠키 동시 보유 테스트.
 *
 * HOST로 인증한 WebSocket은 청중 인증 쿠키가 만료되어도
 * 연결이 유지되어야 한다.
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

        String email = "host-" + UUID.randomUUID() + "@example.com";

        User host = newHost(email);

        LectureRoom room = newRoom(
                host,
                "HOST 쿠키 만료 테스트"
        );

        // 강연자가 자기 방에 청중으로도 참여한 상황이다.
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

        // 실제 HOST 프론트엔드처럼 CONNECT에서 HOST 역할을 명시한다.
        StompSession session = client.connectWithRole(
                StompTestClient.ALLOWED_ORIGIN,
                "HOST",
                loginCookies(email),
                participantCookie
        );

        try {
            assertThat(session.isConnected()).isTrue();

            // 연결 당시 청중 쿠키는 유효해야 한다.
            assertThat(participantExpiresAt)
                    .isAfter(Instant.now());

            long remainingMillis = Math.max(
                    0L,
                    Duration.between(
                            Instant.now(),
                            participantExpiresAt.plusMillis(500)
                    ).toMillis()
            );

            // 청중 인증 TTL이 지난 시점까지 기다린다.
            Thread.sleep(remainingMillis);

            // HOST로 인증한 WebSocket은 유지되어야 한다.
            assertThat(session.isConnected()).isTrue();

            // 기존 채팅 채널도 정상 구독할 수 있어야 한다.
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
            try {
                if (session.isConnected()) {
                    session.disconnect();
                }
            } catch (IllegalStateException ignored) {
                // 테스트 정리 중 이미 연결이 종료된 경우
                // 실제 테스트 결과를 가리지 않도록 한다.
            }
        }
    }
}