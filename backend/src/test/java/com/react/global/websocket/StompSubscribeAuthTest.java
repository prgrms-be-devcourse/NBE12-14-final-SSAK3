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
 * 구독 권한: 그 방의 강연자와 청중만 방별 채널을 구독할 수 있다.
 * 강연자는 실제로 로그인해서 받은 세션 쿠키로 연결해, 로그인 정보가 STOMP 에서도 인식되는지 함께 확인한다.
 */
class StompSubscribeAuthTest extends StompIntegrationTestSupport {
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
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN, participantCookie(roomA));
        var received = client.subscribe(session, chatTopic(roomA));

        assertThat(StompTestClient.awaitBroadcast(received, () -> broadcast(roomA, "안녕하세요")))
                .containsEntry("type", "CHAT_CREATED");
        session.disconnect();
    }

    @Test
    void 방_주인_강연자는_로그인_세션으로_구독하고_방송을_받는다() throws Exception {
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN, loginCookies(hostEmail));
        var received = client.subscribe(session, chatTopic(roomA));

        assertThat(StompTestClient.awaitBroadcast(received, () -> broadcast(roomA, "강연자 화면")))
                .containsEntry("type", "CHAT_CREATED");
        session.disconnect();
    }

    @Test
    void 다른_방_구독은_거절되고_연결이_끊긴다() throws Exception {
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN, participantCookie(roomA));
        client.subscribe(session, chatTopic(roomB));

        assertThat(StompTestClient.awaitClosed(session)).isTrue();
    }

    @Test
    void 쿠키도_로그인도_없으면_구독이_거절된다() throws Exception {
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN);
        client.subscribe(session, chatTopic(roomA));

        assertThat(StompTestClient.awaitClosed(session)).isTrue();
    }

    @Test
    void 다른_강연자는_내_방을_구독할_수_없다() throws Exception {
        String otherEmail = "other-" + UUID.randomUUID() + "@example.com";
        newHost(otherEmail);
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN, loginCookies(otherEmail));
        client.subscribe(session, chatTopic(roomA));

        assertThat(StompTestClient.awaitClosed(session)).isTrue();
    }

    @Test
    void 허용_목록에_없는_주소_구독은_거절된다() throws Exception {
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN, participantCookie(roomA));
        client.subscribe(session, "/topic/rooms/" + roomA.getId() + "/secret");

        assertThat(StompTestClient.awaitClosed(session)).isTrue();
    }

    @Test
    void 강연_상태_알림도_그_방_사람만_구독할_수_있다() throws Exception {
        StompSession ownSession = client.connect(StompTestClient.ALLOWED_ORIGIN, participantCookie(roomA));
        var received = client.subscribe(ownSession, "/topic/rooms/" + roomA.getId() + "/status");

        assertThat(StompTestClient.awaitBroadcast(received, () -> broker.convertAndSend(
                "/topic/rooms/" + roomA.getId() + "/status", Map.of("type", "ROOM_STATUS_CHANGED"))))
                .containsEntry("type", "ROOM_STATUS_CHANGED");
        ownSession.disconnect();

        StompSession otherSession = client.connect(StompTestClient.ALLOWED_ORIGIN, participantCookie(roomA));
        client.subscribe(otherSession, "/topic/rooms/" + roomB.getId() + "/status");

        assertThat(StompTestClient.awaitClosed(otherSession)).isTrue();
    }

    @Test
    void 투표_결과_채널은_방_주인_강연자가_구독하고_방송을_받는다() throws Exception {
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN, loginCookies(hostEmail));
        var received = client.subscribe(session, resultsTopic(roomA));

        assertThat(StompTestClient.awaitBroadcast(received, () -> broker.convertAndSend(
                resultsTopic(roomA), Map.of("type", "POLL_RESULTS_UPDATED"))))
                .containsEntry("type", "POLL_RESULTS_UPDATED");
        session.disconnect();
    }

    @Test
    void 투표_결과_채널은_청중이_구독할_수_없다() throws Exception {
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN, participantCookie(roomA));
        client.subscribe(session, resultsTopic(roomA));

        assertThat(StompTestClient.awaitClosed(session)).isTrue();
    }

    @Test
    void 투표_결과_채널은_다른_강연자도_구독할_수_없다() throws Exception {
        String otherEmail = "other-" + UUID.randomUUID() + "@example.com";
        newHost(otherEmail);
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN, loginCookies(otherEmail));
        client.subscribe(session, resultsTopic(roomA));

        assertThat(StompTestClient.awaitClosed(session)).isTrue();
    }

    @Test
    void 투표_결과_채널은_로그인_없이_구독할_수_없다() throws Exception {
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN);
        client.subscribe(session, resultsTopic(roomA));

        assertThat(StompTestClient.awaitClosed(session)).isTrue();
    }

    @Test
    void 강연자라도_로그인과_청중_쿠키를_같이_보내도_다른_방의_결과_채널은_구독할_수_없다() throws Exception {
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN,
                loginCookies(hostEmail), participantCookie(roomA));
        client.subscribe(session, resultsTopic(otherRoom()));

        assertThat(StompTestClient.awaitClosed(session)).isTrue();
    }

    private void broadcast(LectureRoom room, String content) {
        broker.convertAndSend("/topic/rooms/" + room.getId() + "/chat",
                Map.of("type", "CHAT_CREATED", "data", Map.of("content", content)));
    }

    private static String chatTopic(LectureRoom room) {
        return "/topic/rooms/" + room.getId() + "/chat";
    }

    private static String resultsTopic(LectureRoom room) {
        return "/topic/rooms/" + room.getId() + "/polls/results";
    }

    /** 다른 강연자가 연 방. 이 테스트의 강연자는 소유자가 아니다. */
    private LectureRoom otherRoom() {
        return newRoom(newHost("owner-" + UUID.randomUUID() + "@example.com"), "남의 방");
    }

    /** 청중 입장 서비스로 실제 토큰을 발급받아, 브라우저가 보내는 것과 같은 모양의 쿠키 문자열을 만든다. */
    private String participantCookie(LectureRoom room) {
        var joined = participants.join(room.getJoinCode(), "청중", UUID.randomUUID().toString(), Map.of());
        return "PARTICIPANT_SESSION_" + room.getId() + "=" + joined.rawToken();
    }

}
