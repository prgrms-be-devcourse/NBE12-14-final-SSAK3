package com.react.global.websocket;

import com.react.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 같은 HOST의 여러 WebSocket 사이에서도 개인 오류 메시지가 격리되는지 확인한다. */
class StompChatErrorIsolationTest extends StompIntegrationTestSupport {

    @Test
    void 같은_강연자의_여러_WebSocket에서도_오류는_보낸_소켓에만_전달된다()
            throws Exception {

        String hostEmail = "host-" + UUID.randomUUID() + "@example.com";
        var host = newHost(hostEmail);
        var room = newRoom(host, "오류 격리 테스트");

        String otherEmail = "other-" + UUID.randomUUID() + "@example.com";
        var otherHost = newHost(otherEmail);
        var otherRoom = newRoom(otherHost, "다른 강연방");

        jdbc.sql("UPDATE lecture_rooms SET status = 'LIVE' WHERE id = ?")
                .param(room.getId()).update();

        jdbc.sql("UPDATE lecture_rooms SET status = 'LIVE' WHERE id = ?")
                .param(otherRoom.getId()).update();

        String cookies = loginCookies(hostEmail);

        // 동일한 HOST 로그인 세션으로 두 개의 WebSocket을 연결한다.
        StompSession sessionA = client.connectWithRole(
                StompTestClient.ALLOWED_ORIGIN,
                "HOST",
                cookies
        );

        StompSession sessionB = client.connectWithRole(
                StompTestClient.ALLOWED_ORIGIN,
                "HOST",
                cookies
        );

        try {
            assertThat(sessionA.isConnected()).isTrue();
            assertThat(sessionB.isConnected()).isTrue();

            var errorsA = client.subscribe(sessionA, "/user/queue/errors");
            var errorsB = client.subscribe(sessionB, "/user/queue/errors");

            // B의 오류 우편함이 실제로 등록되었는지 확인한다.
            String attemptB = UUID.randomUUID().toString();

            StompHeaders headersB = new StompHeaders();
            headersB.setDestination("/app/rooms/" + room.getId() + "/chat");
            headersB.set("X-Chat-Attempt", attemptB);

            assertThat(StompTestClient.awaitBroadcast(
                    errorsB,
                    () -> sessionB.send(headersB, Map.of("content", " "))
            )).containsEntry("type", "ERROR")
                    .containsEntry("attempt", attemptB)
                    .containsEntry("code", ErrorCode.INVALID_INPUT.name());

            // A에서 소유하지 않은 강연방으로 채팅 전송을 시도한다.
            String attemptA = UUID.randomUUID().toString();

            StompHeaders headersA = new StompHeaders();
            headersA.setDestination("/app/rooms/" + otherRoom.getId() + "/chat");
            headersA.set("X-Chat-Attempt", attemptA);

            Map<String, Object> error = StompTestClient.awaitBroadcast(
                    errorsA,
                    () -> sessionA.send(headersA, Map.of("content", "다른 방 접근 시도"))
            );

            assertThat(error).isNotNull();
            assertThat(error).containsEntry("type", "ERROR")
                    .containsEntry("attempt", attemptA)
                    .containsEntry("code", ErrorCode.FORBIDDEN.name())
                    .containsEntry("message", ErrorCode.FORBIDDEN.getMessage());

            // B에게 A의 오류가 전달되지 않아야 한다.
            // 워밍업 과정에서 발생한 B 자신의 오류는 허용한다.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);

            while (System.nanoTime() < deadline) {
                long remaining = deadline - System.nanoTime();

                if (remaining <= 0) {
                    break;
                }

                Map<String, Object> received = errorsB.poll(
                        remaining,
                        TimeUnit.NANOSECONDS
                );

                if (received == null) {
                    break;
                }

                assertThat(received)
                        .containsEntry("attempt", attemptB)
                        .containsEntry("code", ErrorCode.INVALID_INPUT.name());
            }

            // 권한이 없는 요청과 잘못된 입력은 저장되지 않아야 한다.
            assertThat(jdbc.sql("""
                    SELECT COUNT(*) FROM chat_messages
                    WHERE lecture_room_id IN (?, ?)
                    """)
                    .params(room.getId(), otherRoom.getId())
                    .query(Long.class)
                    .single()).isZero();

            assertThat(sessionA.isConnected()).isTrue();
            assertThat(sessionB.isConnected()).isTrue();

        } finally {
            if (sessionA.isConnected()) {
                sessionA.disconnect();
            }

            if (sessionB.isConnected()) {
                sessionB.disconnect();
            }
        }
    }
}