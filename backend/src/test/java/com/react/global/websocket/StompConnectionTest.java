package com.react.global.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** STOMP 연결 자체의 허용·거절을 확인한다. 실제 서버를 띄워 진짜 WebSocket 으로 연결한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class StompConnectionTest {
    @LocalServerPort int port;

    @Test
    void 허용된_Origin_은_로그인_없이도_연결할_수_있다() throws Exception {
        var session = new StompTestClient(port).connect(StompTestClient.ALLOWED_ORIGIN);

        assertThat(session.isConnected()).isTrue();
        session.disconnect();
    }

    @Test
    void 허용되지_않은_Origin_의_연결은_거절한다() {
        StompTestClient client = new StompTestClient(port);

        assertThatThrownBy(() -> client.connect("http://evil.example.com"))
                .isInstanceOf(ExecutionException.class);
    }
}
