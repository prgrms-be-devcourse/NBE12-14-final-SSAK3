package com.react.global.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompSession;

import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** STOMP 연결 자체의 허용·거절을 확인한다. 실제 서버를 띄워 진짜 WebSocket 으로 연결한다. */
class StompConnectionTest extends StompIntegrationTestSupport {

    private String hostEmail;

    @BeforeEach
    void prepare() {
        hostEmail = "host-" + UUID.randomUUID() + "@example.com";
        newHost(hostEmail);
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
    void 허용되지_않은_Origin_의_연결은_거절한다()
            throws Exception {

        // 인증이 정상이어도 허용되지 않은 Origin은 거절해야 한다.
        String cookies = loginCookies(hostEmail);

        assertThatThrownBy(() ->
                client.connect(
                        "http://evil.example.com",
                        cookies
                )
        ).isInstanceOf(ExecutionException.class);
    }

    @Test
    void 허용된_Origin이어도_인증이_없으면_연결을_거절한다() {

        // SCRUM-164: 인증되지 않은 사용자는 CONNECT 단계에서 거절한다.
        assertThatThrownBy(() ->
                client.connect(
                        StompTestClient.ALLOWED_ORIGIN
                )
        ).isInstanceOf(ExecutionException.class);
    }
}
