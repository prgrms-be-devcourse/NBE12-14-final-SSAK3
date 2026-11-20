package com.react.global.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class StompTestClient {

    public static final String ALLOWED_ORIGIN =
            "http://localhost:5173";

    private static final long TIMEOUT_SECONDS = 5;
    private static final String SESSION_COOKIE = "HOST_SESSION=";

    private final WebSocketStompClient client;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String url;
    private final String baseUrl;

    public StompTestClient(int port) {
        client = new WebSocketStompClient(
                new StandardWebSocketClient()
        );

        client.setMessageConverter(
                new MappingJackson2MessageConverter()
        );

        httpClient = HttpClient.newHttpClient();
        objectMapper = new ObjectMapper();

        baseUrl = "http://localhost:" + port;
        url = "ws://localhost:" + port + "/ws";
    }

    public StompSession connect(
            String origin,
            String... cookies
    ) throws Exception {

        String existingCookies = Arrays.stream(cookies)
                .filter(cookie ->
                        cookie != null && !cookie.isBlank()
                )
                .collect(Collectors.joining("; "));

        // 1. 기존 쿠키를 사용해 CSRF 토큰 요청
        HttpRequest.Builder request =
                HttpRequest.newBuilder(
                        URI.create(baseUrl + "/api/v1/auth/csrf")
                ).GET();

        if (!existingCookies.isBlank()) {
            request.header(
                    HttpHeaders.COOKIE,
                    existingCookies
            );
        }

        HttpResponse<String> response = httpClient.send(
                request.build(),
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "CSRF 토큰 발급 실패: "
                            + response.statusCode()
            );
        }

        JsonNode data = objectMapper
                .readTree(response.body())
                .path("data");

        String csrfToken = data.path("token").asText();

        if (csrfToken.isBlank()) {
            throw new IllegalStateException(
                    "CSRF 토큰이 없습니다."
            );
        }

        // 2. 새 HTTP 세션 쿠키가 발급되었다면 확보
        String issuedSessionCookie = response.headers()
                .allValues(HttpHeaders.SET_COOKIE)
                .stream()
                .map(value -> value.split(";", 2)[0].trim())
                .filter(value -> value.startsWith(SESSION_COOKIE))
                .findFirst()
                .orElse(null);

        String handshakeCookies = existingCookies;

        if (issuedSessionCookie != null) {
            String otherCookies = Arrays.stream(
                            existingCookies.split(";")
                    )
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .filter(value ->
                            !value.startsWith(SESSION_COOKIE)
                    )
                    .collect(Collectors.joining("; "));

            handshakeCookies = otherCookies.isBlank()
                    ? issuedSessionCookie
                    : otherCookies + "; " + issuedSessionCookie;
        }

        if (!handshakeCookies.contains(SESSION_COOKIE)) {
            throw new IllegalStateException(
                    "HTTP 세션 쿠키가 없습니다."
            );
        }

        // 3. HTTP 핸드셰이크에 쿠키 전달
        WebSocketHttpHeaders handshakeHeaders =
                new WebSocketHttpHeaders();

        handshakeHeaders.setOrigin(origin);
        handshakeHeaders.add(
                HttpHeaders.COOKIE,
                handshakeCookies
        );

        // 4. STOMP CONNECT에 CSRF 헤더 전달
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("X-CSRF-TOKEN", csrfToken);

        return client.connectAsync(
                        url,
                        handshakeHeaders,
                        connectHeaders,
                        new StompSessionHandlerAdapter() {
                        }
                )
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    public BlockingQueue<Map<String, Object>> subscribe(
            StompSession session,
            String destination
    ) {
        BlockingQueue<Map<String, Object>> received =
                new LinkedBlockingQueue<>();

        session.subscribe(
                destination,
                new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return Map.class;
                    }

                    @Override
                    @SuppressWarnings("unchecked")
                    public void handleFrame(
                            StompHeaders headers,
                            Object payload
                    ) {
                        received.add((Map<String, Object>) payload);
                    }
                }
        );

        return received;
    }

    public static Map<String, Object> next(
            BlockingQueue<Map<String, Object>> received
    ) throws InterruptedException {
        return received.poll(
                TIMEOUT_SECONDS,
                TimeUnit.SECONDS
        );
    }

    public static Map<String, Object> awaitBroadcast(
            BlockingQueue<Map<String, Object>> received,
            Runnable broadcast
    ) throws InterruptedException {

        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

        while (System.nanoTime() < deadline) {
            broadcast.run();

            Map<String, Object> message =
                    received.poll(
                            100,
                            TimeUnit.MILLISECONDS
                    );

            if (message != null) {
                return message;
            }
        }

        return null;
    }

    public static Map<String, Object> nextOfType(
            BlockingQueue<Map<String, Object>> received,
            String type,
            long timeoutMillis
    ) throws InterruptedException {

        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);

        while (true) {
            long remainingMillis =
                    TimeUnit.NANOSECONDS.toMillis(
                            deadline - System.nanoTime()
                    );

            if (remainingMillis <= 0) {
                return null;
            }

            Map<String, Object> message =
                    received.poll(
                            remainingMillis,
                            TimeUnit.MILLISECONDS
                    );

            if (message == null) {
                return null;
            }

            if (type.equals(message.get("type"))) {
                return message;
            }
        }
    }

    public static boolean awaitClosed(
            StompSession session
    ) throws InterruptedException {

        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

        while (session.isConnected()
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }

        return !session.isConnected();
    }
}
