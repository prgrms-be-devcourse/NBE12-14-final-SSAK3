package com.react.global.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** STOMP 연결 자체의 허용·거절을 확인한다. 실제 서버를 띄워 진짜 WebSocket 으로 연결한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class StompConnectionTest {
    @LocalServerPort int port;

    @Test
    void 지금은_ws_가_막혀_있어_연결이_거절된다() {
        StompTestClient client = new StompTestClient(port);

        assertThatThrownBy(() -> client.connect(StompTestClient.ALLOWED_ORIGIN))
                .isInstanceOf(ExecutionException.class);
    }
}
