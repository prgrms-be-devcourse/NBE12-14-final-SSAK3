package com.react.global.websocket;

import com.react.auth.entity.User;
import com.react.room.entity.LectureRoom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompSession;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** STOMP 연결 자체의 허용·거절을 확인한다. 실제 서버를 띄워 진짜 WebSocket 으로 연결한다. */
class StompConnectionTest extends StompIntegrationTestSupport {

    private String hostEmail;
    private User host;

    @BeforeEach
    void prepare() {
        hostEmail = "host-" + UUID.randomUUID() + "@example.com";
        host = newHost(hostEmail);
    }

    @Test
    void 허용된_Origin과_유효한_HOST_인증으로_연결할_수_있다()
            throws Exception {
        StompSession session = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                loginCookies(hostEmail)
        );

        assertThat(session.isConnected()).isTrue();
        session.disconnect();
    }

    @Test
    void 허용된_Origin과_유효한_청중_인증으로_연결할_수_있다()
            throws Exception {
        LectureRoom room = newRoom(host, "연결 테스트");

        StompSession session = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                participantCookie(room)
        );

        assertThat(session.isConnected()).isTrue();
        session.disconnect();
    }

    @Test
    void HOST와_청중_쿠키가_함께_있어도_청중_역할로_연결할_수_있다()
            throws Exception {
        LectureRoom room = newRoom(host, "역할 선택 테스트");

        StompSession session = client.connectWithRole(
                StompTestClient.ALLOWED_ORIGIN,
                "PARTICIPANT",
                loginCookies(hostEmail),
                participantCookie(room)
        );

        assertThat(session.isConnected()).isTrue();
        session.disconnect();
    }

    @Test
    void 청중_쿠키가_없으면_청중_역할로_연결할_수_없다()
            throws Exception {
        String cookies = loginCookies(hostEmail);

        assertThatThrownBy(() ->
                client.connectWithRole(
                        StompTestClient.ALLOWED_ORIGIN,
                        "PARTICIPANT",
                        cookies
                )
        ).isInstanceOf(ExecutionException.class);
    }

    @Test
    void 유효한_HOST_인증이어도_CSRF_헤더가_없으면_연결을_거절한다()
            throws Exception {
        String cookies = loginCookies(hostEmail);

        assertThatThrownBy(() ->
                client.connectWithoutCsrf(
                        StompTestClient.ALLOWED_ORIGIN,
                        cookies
                )
        ).isInstanceOf(ExecutionException.class);
    }

    @Test
    void 허용된_Origin이어도_인증이_없으면_연결을_거절한다() {
        assertThatThrownBy(() ->
                client.connect(StompTestClient.ALLOWED_ORIGIN)
        ).isInstanceOf(ExecutionException.class);
    }

    @Test
    void 허용되지_않은_Origin_의_연결은_거절한다()
            throws Exception {
        String cookies = loginCookies(hostEmail);

        assertThatThrownBy(() ->
                client.connect("http://evil.example.com", cookies)
        ).isInstanceOf(ExecutionException.class);
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
}