
package com.react.global.websocket;

import com.react.auth.entity.User;
import com.react.room.entity.LectureRoom;
import com.react.room.service.LectureRoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 구독 권한: 그 방의 강연자와 청중만 방별 채널을 구독할 수 있다.
 * 강연자는 실제로 로그인해서 받은 세션 쿠키로 연결해,
 * 로그인 정보가 STOMP 에서도 인식되는지 함께 확인한다.
 */
class StompSubscribeAuthTest extends StompIntegrationTestSupport {

    @Autowired LectureRoomService roomService;
    @Autowired TransactionTemplate transactions;

    private User host;
    private String hostEmail;
    private LectureRoom roomA;
    private LectureRoom roomB;

    @BeforeEach
    void prepare() {
        hostEmail = "host-" + UUID.randomUUID() + "@example.com";
        host = newHost(hostEmail);
        roomA = newRoom(host, "구독 테스트");
        roomB = newRoom(host, "구독 테스트");
    }

    @Test
    void 그_방_청중은_구독하고_방송을_받는다() throws Exception {
        StompSession session = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                participantCookie(roomA)
        );

        var received = client.subscribe(session, chatTopic(roomA));

        assertThat(StompTestClient.awaitBroadcast(
                received,
                () -> broadcast(roomA, "안녕하세요")
        )).containsEntry("type", "CHAT_CREATED");

        session.disconnect();
    }

    @Test
    void 방_주인_강연자는_로그인_세션으로_구독하고_방송을_받는다()
            throws Exception {

        StompSession session = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                loginCookies(hostEmail)
        );

        var received = client.subscribe(session, chatTopic(roomA));

        assertThat(StompTestClient.awaitBroadcast(
                received,
                () -> broadcast(roomA, "강연자 화면")
        )).containsEntry("type", "CHAT_CREATED");

        session.disconnect();
    }

    @Test
    void 다른_방_구독은_거절되고_연결이_끊긴다() throws Exception {
        StompSession session = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                participantCookie(roomA)
        );

        client.subscribe(session, chatTopic(roomB));

        assertThat(StompTestClient.awaitClosed(session)).isTrue();
    }

    @Test
    void 쿠키도_로그인도_없으면_CONNECT부터_거절된다() {
        // SCRUM-164: 인증 정보가 없으면 구독 전 CONNECT 단계에서 거절한다.
        assertThatThrownBy(
                () -> client.connect(StompTestClient.ALLOWED_ORIGIN)
        ).isInstanceOf(ExecutionException.class);
    }

    @Test
    void 다른_강연자는_내_방을_구독할_수_없다() throws Exception {
        String otherEmail = "other-" + UUID.randomUUID() + "@example.com";
        newHost(otherEmail);

        StompSession session = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                loginCookies(otherEmail)
        );

        client.subscribe(session, chatTopic(roomA));

        assertThat(StompTestClient.awaitClosed(session)).isTrue();
    }

    @Test
    void 허용_목록에_없는_주소_구독은_거절된다() throws Exception {
        StompSession session = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                participantCookie(roomA)
        );

        client.subscribe(
                session,
                "/topic/rooms/" + roomA.getId() + "/secret"
        );

        assertThat(StompTestClient.awaitClosed(session)).isTrue();
    }

    @Test
    void 강연_상태_알림도_그_방_사람만_구독할_수_있다()
            throws Exception {

        StompSession ownSession = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                participantCookie(roomA)
        );

        var received = client.subscribe(
                ownSession,
                "/topic/rooms/" + roomA.getId() + "/status"
        );

        assertThat(StompTestClient.awaitBroadcast(
                received,
                () -> broker.convertAndSend(
                        "/topic/rooms/" + roomA.getId() + "/status",
                        Map.of("type", "ROOM_STATUS_CHANGED")
                )
        )).containsEntry("type", "ROOM_STATUS_CHANGED");

        ownSession.disconnect();

        StompSession otherSession = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                participantCookie(roomA)
        );

        client.subscribe(
                otherSession,
                "/topic/rooms/" + roomB.getId() + "/status"
        );

        assertThat(StompTestClient.awaitClosed(otherSession)).isTrue();
    }

    @Test
    void 준비_방_삭제가_커밋된_뒤에만_대기_중인_청중에게_알린다()
            throws Exception {

        var hostAuth = UsernamePasswordAuthenticationToken.authenticated(
                hostEmail,
                null,
                AuthorityUtils.createAuthorityList("ROLE_HOST")
        );

        var joined = participants.join(
                roomA.getJoinCode(),
                "대기자",
                UUID.randomUUID().toString(),
                Map.of()
        );

        String cookie =
                "PARTICIPANT_SESSION_" + roomA.getId()
                        + "=" + joined.rawToken();

        StompSession session = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                cookie
        );

        try {
            String topic =
                    "/topic/rooms/" + roomA.getId() + "/status";

            var received = client.subscribe(session, topic);

            assertThat(StompTestClient.awaitBroadcast(
                    received,
                    () -> broker.convertAndSend(
                            topic,
                            Map.of("type", "TEST_SUBSCRIBED")
                    )
            )).isNotNull();

            transactions.executeWithoutResult(tx -> {
                roomService.deleteReadyRoom(roomA.getId(), hostAuth);
                tx.setRollbackOnly();
            });

            assertThat(
                    participants.me(roomA.getId(), joined.rawToken()).roomId()
            ).isEqualTo(roomA.getId());

            assertThat(
                    StompTestClient.nextOfType(
                            received,
                            "ROOM_DELETED",
                            400
                    )
            ).isNull();

            roomService.deleteReadyRoom(roomA.getId(), hostAuth);

            var event = StompTestClient.nextOfType(
                    received,
                    "ROOM_DELETED",
                    5000
            );

            assertThat(event).isNotNull();
            assertThat(
                    json.valueToTree(event).at("/data/roomId").longValue()
            ).isEqualTo(roomA.getId());

        } finally {
            session.disconnect();
        }
    }

    @Test
    void 투표_결과_채널은_방_주인_강연자가_구독하고_방송을_받는다()
            throws Exception {

        StompSession session = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                loginCookies(hostEmail)
        );

        var received = client.subscribe(
                session,
                resultsTopic(roomA)
        );

        assertThat(StompTestClient.awaitBroadcast(
                received,
                () -> broker.convertAndSend(
                        resultsTopic(roomA),
                        Map.of("type", "POLL_RESULTS_UPDATED")
                )
        )).containsEntry("type", "POLL_RESULTS_UPDATED");

        session.disconnect();
    }

    @Test
    void 투표_결과_채널은_청중이_구독할_수_없다() throws Exception {
        StompSession session = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                participantCookie(roomA)
        );

        client.subscribe(session, resultsTopic(roomA));

        assertThat(StompTestClient.awaitClosed(session)).isTrue();
    }

    @Test
    void 투표_결과_채널은_다른_강연자도_구독할_수_없다()
            throws Exception {

        String otherEmail = "other-" + UUID.randomUUID() + "@example.com";
        newHost(otherEmail);

        StompSession session = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                loginCookies(otherEmail)
        );

        client.subscribe(session, resultsTopic(roomA));

        assertThat(StompTestClient.awaitClosed(session)).isTrue();
    }

    @Test
    void 투표_결과_채널은_로그인_없이_구독할_수_없다()
            throws Exception {

        // SCRUM-164: 무인증 연결은 CONNECT에서 거절한다.
        assertThatThrownBy(
                () -> client.connect(StompTestClient.ALLOWED_ORIGIN)
        ).isInstanceOf(ExecutionException.class);
    }

    @Test
    void 강연자라도_로그인과_청중_쿠키를_같이_보내도_다른_방의_결과_채널은_구독할_수_없다()
            throws Exception {

        StompSession session = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                loginCookies(hostEmail),
                participantCookie(roomA)
        );

        client.subscribe(session, resultsTopic(otherRoom()));

        assertThat(StompTestClient.awaitClosed(session)).isTrue();
    }

    // SCRUM-164: 명시적 HOST 연결에서 청중 쿠키 권한 재사용 차단
    @Test
    void HOST_연결은_다른_강연방의_청중_쿠키가_있어도_역할_헤더_없이_구독할_수_없다()
            throws Exception {

        // 현재 HOST가 소유하지 않은 다른 강연방
        LectureRoom foreignRoom = otherRoom();

        // 해당 방의 정상적인 청중 쿠키도 함께 보유한 상황
        String foreignParticipantCookie =
                participantCookie(foreignRoom);

        // CONNECT에서는 HOST 역할을 명시한다.
        StompSession session = client.connectWithRole(
                StompTestClient.ALLOWED_ORIGIN,
                "HOST",
                loginCookies(hostEmail),
                foreignParticipantCookie
        );

        try {
            assertThat(session.isConnected()).isTrue();

            // 후속 SUBSCRIBE에서는 역할 헤더를 보내지 않는다.
            // 다른 강연방의 청중 쿠키로 구독 권한을 얻어서는 안 된다.
            client.subscribe(
                    session,
                    chatTopic(foreignRoom)
            );

            // 권한 없는 구독은 거절되어 연결이 종료되어야 한다.
            assertThat(
                    StompTestClient.awaitClosed(session)
            ).isTrue();

        } finally {
            try {
                if (session.isConnected()) {
                    session.disconnect();
                }
            } catch (IllegalStateException ignored) {
                // 이미 종료된 연결은 정리 예외를 무시한다.
            }
        }
    }

    private void broadcast(LectureRoom room, String content) {
        broker.convertAndSend(
                "/topic/rooms/" + room.getId() + "/chat",
                Map.of(
                        "type", "CHAT_CREATED",
                        "data", Map.of("content", content)
                )
        );
    }

    private static String chatTopic(LectureRoom room) {
        return "/topic/rooms/" + room.getId() + "/chat";
    }

    private static String resultsTopic(LectureRoom room) {
        return "/topic/rooms/" + room.getId() + "/polls/results";
    }

    /** 다른 강연자가 연 방. 이 테스트의 강연자는 소유자가 아니다. */
    private LectureRoom otherRoom() {
        return newRoom(
                newHost("owner-" + UUID.randomUUID() + "@example.com"),
                "남의 방"
        );
    }

    /**
     * 청중 입장 서비스로 실제 토큰을 발급받아,
     * 브라우저가 보내는 것과 같은 모양의 쿠키 문자열을 만든다.
     */
    private String participantCookie(LectureRoom room) {
        var joined = participants.join(
                room.getJoinCode(),
                "청중",
                UUID.randomUUID().toString(),
                Map.of()
        );

        return "PARTICIPANT_SESSION_" + room.getId()
                + "=" + joined.rawToken();
    }
}
