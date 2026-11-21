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
 *
 * 세션 만료 시각이 지난 후 기존 쿠키로 HTTP 요청을 보내
 * 서버가 만료를 감지하도록 한다.
 *
 * 이 테스트는 세션 만료 감지 후 WebSocket 종료를 검증한다.
 * 별도 HTTP 요청 없이 정확히 2초에 세션이 제거되는지까지
 * 검증하는 테스트는 아니다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.session.cookie.secure=false"
        }
)
@ActiveProfiles("test")
@Import(
        StompHttpSessionExpiryIntegrationTest
                .ShortSessionTestController.class
)
class StompHttpSessionExpiryIntegrationTest
        extends StompIntegrationTestSupport {

    @Test
    void HTTP_세션_만료를_감지하면_WebSocket도_종료된다()
            throws Exception {

        // 1. 테스트용 HOST 생성
        String email =
                "host-" + UUID.randomUUID() + "@example.com";

        newHost(email);

        // 2. HTTP 로그인 후 HOST_SESSION 쿠키 확보
        String cookies = loginCookies(email);

        // 3. 정상적인 HOST 인증으로 WebSocket 연결
        StompSession session = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                cookies
        );

        try {
            assertThat(session.isConnected()).isTrue();

            HttpClient http = HttpClient.newHttpClient();

            String baseUrl =
                    "http://localhost:" + port;

            // 4. 현재 로그인한 HTTP 세션에만
            // 테스트용 유효기간 2초를 직접 설정한다.
            HttpRequest shortenRequest = HttpRequest.newBuilder(
                            URI.create(
                                    baseUrl
                                            + "/__test__/sessions/shorten"
                            )
                    )
                    .header(HttpHeaders.COOKIE, cookies)
                    .GET()
                    .build();

            HttpResponse<String> shortenResponse =
                    http.send(
                            shortenRequest,
                            HttpResponse.BodyHandlers.ofString()
                    );

            assertThat(shortenResponse.statusCode())
                    .isEqualTo(200);

            // 실제 세션에 2초가 설정되었는지 확인
            assertThat(shortenResponse.body())
                    .isEqualTo("2");

            // 5. 마지막 HTTP 요청 이후 충분히 기다린다.
            // 별도의 SEND 요청은 하지 않는다.
            Thread.sleep(3500);

            // 6. 기존 쿠키로 HTTP 요청을 보내
            // 서버가 만료된 HTTP 세션을 확인하도록 한다.
            String url =
                    baseUrl + "/api/v1/auth/me";

            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(url)
                    )
                    .header(HttpHeaders.COOKIE, cookies)
                    .GET()
                    .build();

            HttpResponse<String> response = http.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            // 만료된 세션으로는 HOST 인증 API에
            // 더 이상 접근할 수 없어야 한다.
            assertThat(response.statusCode())
                    .isEqualTo(401);

            // 7. 이전 HTTP 세션에 연결됐던 WebSocket은
            // 더 이상 유지되면 안 된다.
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
     * 통합 테스트에서만 사용하는 HTTP 세션 설정 API.
     *
     * 실제 운영 코드에는 추가하지 않는다.
     *
     * 로그인한 사용자의 현재 HTTP 세션에만
     * 2초의 비활성 타임아웃을 설정한다.
     */
    @RestController
    static class ShortSessionTestController {

        @GetMapping("/__test__/sessions/shorten")
        public String shortenSession(
                HttpServletRequest request
        ) {
            HttpSession session =
                    request.getSession(false);

            if (session == null) {
                throw new IllegalStateException(
                        "로그인된 HTTP 세션이 없습니다."
                );
            }

            // Tomcat 기본 세션 타임아웃 대신
            // 이 세션 인스턴스에 직접 초 단위 설정
            session.setMaxInactiveInterval(2);

            // 실제 설정값을 테스트에서 확인한다.
            return String.valueOf(
                    session.getMaxInactiveInterval()
            );
        }
    }
}
