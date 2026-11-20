package com.react.global.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCRUM-164 실제 WebSocket 연결 수명과 CSRF 인증을 검증한다.
 *
 * HTTP 로그인·로그아웃과 STOMP 연결을 함께 사용한다.
 * 단순 메서드 호출이 아니라 실제 서버를 통해
 * HTTP 세션의 변경이 WebSocket에 반영되는지 확인한다.
 */
class StompSessionLifecycleIntegrationTest
        extends StompIntegrationTestSupport {

    @Test
    void HOST가_로그아웃하면_기존_WebSocket이_종료된다()
            throws Exception {

        String email = createHost();

        // 실제 HTTP 로그인을 통해 세션 쿠키 발급
        String cookies = loginCookies(email);

        // 발급받은 세션으로 WebSocket 연결
        StompSession session = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                cookies
        );

        assertThat(session.isConnected()).isTrue();

        // 실제 로그아웃 API 호출
        HttpResponse<String> response = postWithCsrf(
                cookies,
                "/api/v1/auth/logout",
                null
        );

        assertThat(response.statusCode()).isEqualTo(204);

        // HTTP 세션이 무효화되면
        // 이미 연결된 WebSocket도 종료되어야 한다.
        assertThat(
                StompTestClient.awaitClosed(session)
        ).isTrue();
    }

    @Test
    void HOST가_다른_계정으로_재로그인하면_기존_WebSocket이_종료된다()
            throws Exception {

        String firstEmail = createHost();
        String secondEmail = createHost();

        // 첫 번째 강연자로 로그인
        String cookies = loginCookies(firstEmail);

        StompSession session = client.connect(
                StompTestClient.ALLOWED_ORIGIN,
                cookies
        );

        assertThat(session.isConnected()).isTrue();

        // 동일 HTTP 세션에서 다른 계정으로 다시 로그인
        String requestBody = json.writeValueAsString(
                Map.of(
                        "email", secondEmail,
                        "password", PASSWORD
                )
        );

        HttpResponse<String> response = postWithCsrf(
                cookies,
                "/api/v1/auth/login",
                requestBody
        );

        assertThat(response.statusCode()).isEqualTo(200);

        // 이전 계정에서 만든 WebSocket은
        // 새로운 로그인 이후 유지되면 안 된다.
        assertThat(
                StompTestClient.awaitClosed(session)
        ).isTrue();
    }

    @Test
    void STOMP_CONNECT에_CSRF_토큰이_없으면_거절된다()
            throws Exception {

        String email = createHost();
        String cookies = loginCookies(email);

        // 정상 로그인 쿠키는 전달하지만
        // STOMP CONNECT에 CSRF 헤더를 넣지 않는다.
        assertThatThrownBy(() ->
                connectWithCsrf(cookies, null)
        ).isInstanceOf(ExecutionException.class);
    }

    @Test
    void STOMP_CONNECT에_위조된_CSRF_토큰이_있으면_거절된다()
            throws Exception {

        String email = createHost();
        String cookies = loginCookies(email);

        // HTTP 세션은 정상이어도
        // 잘못된 CSRF 토큰으로는 CONNECT할 수 없다.
        assertThatThrownBy(() ->
                connectWithCsrf(
                        cookies,
                        "invalid-csrf-token"
                )
        ).isInstanceOf(ExecutionException.class);
    }

    /**
     * 테스트마다 새로운 HOST를 만든다.
     * 이메일 중복으로 인한 테스트 간 간섭을 방지한다.
     */
    private String createHost() {
        String email =
                "host-" + UUID.randomUUID() + "@example.com";

        newHost(email);

        return email;
    }

    /**
     * 실제 HTTP API를 호출한다.
     *
     * 기존 로그인 세션 쿠키로 CSRF 토큰을 조회한 뒤
     * 같은 세션으로 POST 요청을 전송한다.
     */
    private HttpResponse<String> postWithCsrf(
            String cookies,
            String path,
            String requestBody
    ) throws Exception {

        HttpClient http = HttpClient.newHttpClient();

        String baseUrl =
                "http://localhost:" + port;

        // 1. 기존 세션의 CSRF 토큰 조회
        HttpRequest csrfRequest = HttpRequest.newBuilder(
                        URI.create(
                                baseUrl + "/api/v1/auth/csrf"
                        )
                )
                .header(HttpHeaders.COOKIE, cookies)
                .GET()
                .build();

        HttpResponse<String> csrfResponse = http.send(
                csrfRequest,
                HttpResponse.BodyHandlers.ofString()
        );

        assertThat(csrfResponse.statusCode())
                .isEqualTo(200);

        String csrfToken = json.readTree(
                        csrfResponse.body()
                )
                .path("data")
                .path("token")
                .asText();

        assertThat(csrfToken).isNotBlank();

        // 2. 기존 세션 쿠키와 CSRF 헤더로 POST 요청
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create(baseUrl + path)
                )
                .header(HttpHeaders.COOKIE, cookies)
                .header("X-CSRF-TOKEN", csrfToken);

        if (requestBody == null) {
            request.POST(
                    HttpRequest.BodyPublishers.noBody()
            );
        } else {
            request.header(
                    HttpHeaders.CONTENT_TYPE,
                    "application/json"
            );

            request.POST(
                    HttpRequest.BodyPublishers.ofString(
                            requestBody
                    )
            );
        }

        return http.send(
                request.build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    /**
     * STOMP CONNECT의 CSRF 헤더를 직접 지정한다.
     *
     * 기존 StompTestClient는 정상 CSRF 토큰을
     * 자동으로 넣기 때문에, 누락·위조 상황은
     * 별도의 클라이언트를 사용해서 검증한다.
     */
    private StompSession connectWithCsrf(
            String cookies,
            String csrfToken
    ) throws Exception {

        WebSocketStompClient stompClient =
                new WebSocketStompClient(
                        new StandardWebSocketClient()
                );

        // WebSocket 핸드셰이크에는 정상 쿠키 전달
        WebSocketHttpHeaders handshakeHeaders =
                new WebSocketHttpHeaders();

        handshakeHeaders.setOrigin(
                StompTestClient.ALLOWED_ORIGIN
        );

        handshakeHeaders.add(
                HttpHeaders.COOKIE,
                cookies
        );

        // STOMP CONNECT 헤더에는
        // 테스트에서 지정한 CSRF 토큰만 전달
        StompHeaders connectHeaders =
                new StompHeaders();

        if (csrfToken != null) {
            connectHeaders.add(
                    "X-CSRF-TOKEN",
                    csrfToken
            );
        }

        String url =
                "ws://localhost:" + port + "/ws";

        return stompClient.connectAsync(
                        url,
                        handshakeHeaders,
                        connectHeaders,
                        new StompSessionHandlerAdapter() {
                        }
                )
                .get(5, TimeUnit.SECONDS);
    }
}
