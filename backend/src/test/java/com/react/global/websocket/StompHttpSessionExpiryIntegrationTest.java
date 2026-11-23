package com.react.global.websocket;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-164 HTTP 세션 자연 만료와 WebSocket 종료를 검증한다.
 *
 * HTTP 세션 유효기간을 테스트에서만 2초로 설정한다.
 * 세션 만료 후 HTTP 요청으로 만료를 감지시키고
 * 기존 WebSocket 연결도 종료되는지 확인한다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.session.cookie.secure=false"
        }
)
@ActiveProfiles("test")
@Import(StompHttpSessionExpiryIntegrationTest.ShortSessionTestController.class)
class StompHttpSessionExpiryIntegrationTest
        extends StompIntegrationTestSupport {

    @Test
    void HTTP_세션_만료를_감지하면_WebSocket도_종료된다()
            throws Exception {

        String email = "host-" + UUID.randomUUID() + "@example.com";
        newHost(email);

        String cookies = loginCookies(email);

        StompSession session = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                cookies
        );

        try {
            assertThat(session.isConnected()).isTrue();

            HttpClient http = HttpClient.newHttpClient();
            String baseUrl = "http://localhost:" + port;

            // 현재 HTTP 세션의 유효기간을 2초로 변경한다.
            HttpRequest shortenRequest = HttpRequest.newBuilder(
                            URI.create(baseUrl + "/__test__/sessions/shorten")
                    )
                    .header(HttpHeaders.COOKIE, cookies)
                    .GET()
                    .build();

            HttpResponse<String> shortenResponse = http.send(
                    shortenRequest,
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(shortenResponse.statusCode()).isEqualTo(200);
            assertThat(shortenResponse.body()).isEqualTo("2");

            // HTTP 세션 유효기간보다 충분히 기다린다.
            Thread.sleep(3500);

            // 서버가 만료된 세션을 감지하도록 기존 쿠키로 요청한다.
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(baseUrl + "/api/v1/auth/me")
                    )
                    .header(HttpHeaders.COOKIE, cookies)
                    .GET()
                    .build();

            HttpResponse<String> response = http.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(response.statusCode()).isEqualTo(401);

            // 세션이 만료되면 이전 WebSocket도 종료되어야 한다.
            assertThat(
                    StompTestClient.awaitClosed(session)
            ).isTrue();

        } finally {
            if (session.isConnected()) {
                session.disconnect();
            }
        }
    }

    /**
     * 테스트에서만 사용하는 세션 만료 시간 변경 API.
     * 운영 코드에는 추가하지 않는다.
     */
    @RestController
    static class ShortSessionTestController {

        @GetMapping("/__test__/sessions/shorten")
        public String shortenSession(HttpServletRequest request) {

            HttpSession session = request.getSession(false);

            if (session == null) {
                throw new IllegalStateException(
                        "로그인된 HTTP 세션이 없습니다."
                );
            }

            session.setMaxInactiveInterval(2);

            return String.valueOf(
                    session.getMaxInactiveInterval()
            );
        }
    }
}