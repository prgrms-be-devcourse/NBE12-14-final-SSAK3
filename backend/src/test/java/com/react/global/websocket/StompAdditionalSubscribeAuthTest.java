package com.react.global.websocket;

import com.react.auth.entity.User;
import com.react.room.entity.LectureRoom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompSession;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-164 추가 구독 채널의 접근 권한을 검증한다.
 *
 * polls는 해당 방의 HOST와 PARTICIPANT가 구독할 수 있다.
 * ai는 해당 방의 소유자 HOST만 구독할 수 있다.
 */
class StompAdditionalSubscribeAuthTest
        extends StompIntegrationTestSupport {

    private String hostEmail;
    private LectureRoom roomA;
    private LectureRoom roomB;

    @BeforeEach
    void prepare() {
        hostEmail =
                "host-" + UUID.randomUUID() + "@example.com";

        User host = newHost(hostEmail);

        roomA = newRoom(host, "추가 구독 테스트 A");
        roomB = newRoom(host, "추가 구독 테스트 B");
    }

    @Test
    void 청중은_참여한_방의_투표_채널을_구독한다()
            throws Exception {

        StompSession session = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                participantCookie(roomA)
        );

        try {
            String destination = pollsTopic(roomA);

            var received = client.subscribe(
                    session,
                    destination
            );

            assertThat(
                    StompTestClient.awaitBroadcast(
                            received,
                            () -> broadcast(
                                    destination,
                                    "POLL_CREATED"
                            )
                    )
            ).containsEntry("type", "POLL_CREATED");

        } finally {
            session.disconnect();
        }
    }

    @Test
    void 방_주인_강연자는_투표_채널을_구독한다()
            throws Exception {

        StompSession session = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                loginCookies(hostEmail)
        );

        try {
            String destination = pollsTopic(roomA);

            var received = client.subscribe(
                    session,
                    destination
            );

            assertThat(
                    StompTestClient.awaitBroadcast(
                            received,
                            () -> broadcast(
                                    destination,
                                    "POLL_CLOSED"
                            )
                    )
            ).containsEntry("type", "POLL_CLOSED");

        } finally {
            session.disconnect();
        }
    }

    @Test
    void 다른_방_청중은_투표_채널을_구독할_수_없다()
            throws Exception {

        StompSession session = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                participantCookie(roomA)
        );

        client.subscribe(
                session,
                pollsTopic(roomB)
        );

        assertThat(
                StompTestClient.awaitClosed(session)
        ).isTrue();
    }

    @Test
    void 방_주인_강연자는_AI_채널을_구독한다()
            throws Exception {

        StompSession session = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                loginCookies(hostEmail)
        );

        try {
            String destination = aiTopic(roomA);

            var received = client.subscribe(
                    session,
                    destination
            );

            assertThat(
                    StompTestClient.awaitBroadcast(
                            received,
                            () -> broadcast(
                                    destination,
                                    "AI_ANALYSIS_COMPLETED"
                            )
                    )
            ).containsEntry(
                    "type",
                    "AI_ANALYSIS_COMPLETED"
            );

        } finally {
            session.disconnect();
        }
    }

    @Test
    void 청중은_AI_채널을_구독할_수_없다()
            throws Exception {

        StompSession session = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                participantCookie(roomA)
        );

        client.subscribe(
                session,
                aiTopic(roomA)
        );

        assertThat(
                StompTestClient.awaitClosed(session)
        ).isTrue();
    }

    @Test
    void 다른_강연자는_AI_채널을_구독할_수_없다()
            throws Exception {

        String otherEmail =
                "other-" + UUID.randomUUID() + "@example.com";

        newHost(otherEmail);

        StompSession session = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                loginCookies(otherEmail)
        );

        client.subscribe(
                session,
                aiTopic(roomA)
        );

        assertThat(
                StompTestClient.awaitClosed(session)
        ).isTrue();
    }

    private void broadcast(
            String destination,
            String type
    ) {
        broker.convertAndSend(
                destination,
                Map.of("type", type)
        );
    }

    private String participantCookie(LectureRoom room) {
        var joined = participants.join(
                room.getJoinCode(),
                "청중",
                UUID.randomUUID().toString(),
                Map.of()
        );

        return "PARTICIPANT_SESSION_"
                + room.getId()
                + "="
                + joined.rawToken();
    }

    private static String pollsTopic(LectureRoom room) {
        return "/topic/rooms/"
                + room.getId()
                + "/polls";
    }

    private static String aiTopic(LectureRoom room) {
        return "/topic/rooms/"
                + room.getId()
                + "/ai";
    }
}
