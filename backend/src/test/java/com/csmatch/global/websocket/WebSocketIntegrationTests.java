package com.csmatch.global.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.Base64;
import java.util.Date;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://${TEST_MYSQL_HOST:localhost}:${TEST_MYSQL_PORT:3306}/csmatch_ws_test?serverTimezone=Asia/Seoul&characterEncoding=UTF-8",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
class WebSocketIntegrationTests {
    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Test
    void realUpgradeAuthenticationAndPingWork() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var account = signupAndLogin(client);
            var listener = new Messages();
            var socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws"), listener).get(5, TimeUnit.SECONDS);
            try {
                socket.sendText(authMessage(account.accessToken()), true).join();
                String response = listener.next();
                assertThat(response).doesNotContain(account.accessToken());
                var success = mapper.readTree(response);
                assertThat(success.path("type").asText()).isEqualTo("AUTH_SUCCESS");
                assertThat(success.path("payload").path("userId").asLong()).isEqualTo(account.userId());
                socket.sendText("{\"type\":\"PING\",\"payload\":{}}", true).join();
                assertThat(listener.next()).contains("PONG", "serverTime");
            } finally {
                socket.abort();
            }
        }
    }

    @Test
    void heartbeatBeforeAuthenticationIsRejected() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var listener = new Messages();
            var socket = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws"), listener).get(5, TimeUnit.SECONDS);
            try {
                socket.sendText("{\"type\":\"PING\",\"payload\":{}}", true).join();
                var error = new ObjectMapper().readTree(listener.next());
                assertThat(error.path("type").asText()).isEqualTo("ERROR");
                assertThat(error.path("payload").path("code").asText()).isEqualTo("AUTH_REQUIRED");
                assertThat(error.path("payload").path("message").asText()).isNotBlank();
                assertThat(listener.next()).isEqualTo("CLOSE:1008:AUTH_REQUIRED");
            } finally {
                socket.abort();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"malformed,VALIDATION_FAILED,INVALID_MESSAGE", "bad-token,AUTH_REQUIRED,AUTH_FAILED"})
    void invalidRequestsReceiveErrorBeforeClose(String scenario, String code, String reason) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var listener = new Messages();
            var socket = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws"), listener).get(5, TimeUnit.SECONDS);
            try {
                String request = "malformed".equals(scenario) ? "{"
                        : "{\"type\":\"AUTH\",\"payload\":{\"accessToken\":\"do-not-echo-this\"}}";
                socket.sendText(request, true).join();
                String response = listener.next();
                assertThat(response).doesNotContain("do-not-echo-this");
                var error = new ObjectMapper().readTree(response);
                assertThat(error.path("type").asText()).isEqualTo("ERROR");
                assertThat(error.path("payload").path("code").asText()).isEqualTo(code);
                assertThat(error.path("payload").path("message").asText()).isNotBlank();
                assertThat(error.has("error")).isFalse();
                assertThat(error.path("payload").has("error")).isFalse();
                assertThat(listener.next()).isEqualTo("CLOSE:1008:" + reason);
            } finally {
                socket.abort();
            }
        }
    }

    @Test
    void foreignOriginIsRejectedAndHttpPathsRemainProtected() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            assertThatThrownBy(() -> client.newWebSocketBuilder().header("Origin", "https://untrusted.example")
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new Messages())
                    .get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(java.net.http.WebSocketHandshakeException.class);
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/private"))
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(401);
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"tampered", "expired", "withdrawn", "deleted"})
    void invalidJwtOrInactiveMemberIsRejected(String scenario) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var account = signupAndLogin(client);
            String token = account.accessToken();
            switch (scenario) {
                case "tampered" -> {
                    var parts = token.split("\\.");
                    byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
                    signature[0] ^= 1;
                    token = parts[0] + "." + parts[1] + "."
                            + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
                }
                case "expired" -> token = Jwts.builder().issuer("csmatch")
                        .subject(Long.toString(account.userId())).claim("type", "access")
                        .issuedAt(Date.from(Instant.now().minusSeconds(7200)))
                        .expiration(Date.from(Instant.now().minusSeconds(3600)))
                        .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                        .compact();
                case "withdrawn" -> assertThat(jdbcTemplate.update(
                        "UPDATE users SET status = 'WITHDRAWN' WHERE user_id = ?", account.userId())).isEqualTo(1);
                case "deleted" -> assertThat(jdbcTemplate.update(
                        "DELETE FROM users WHERE user_id = ?", account.userId())).isEqualTo(1);
                default -> throw new IllegalArgumentException(scenario);
            }
            var listener = new Messages();
            var socket = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws"), listener).get(5, TimeUnit.SECONDS);
            try {
                socket.sendText(authMessage(token), true).join();
                String response = listener.next();
                assertThat(response).doesNotContain(token);
                var error = mapper.readTree(response);
                assertThat(error.path("type").asText()).isEqualTo("ERROR");
                assertThat(error.path("payload").path("code").asText()).isEqualTo("AUTH_REQUIRED");
                assertThat(listener.next()).isEqualTo("CLOSE:1008:AUTH_FAILED");
            } finally {
                socket.abort();
            }
        }
    }

    private TestAccount signupAndLogin(HttpClient client) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String email = suffix + "@example.com";
        String password = "test-password123";
        var signup = post(client, "/api/v1/auth/signup", Map.of(
                "email", email, "password", password, "nickname", "ws-" + suffix.substring(0, 20)));
        assertThat(signup.statusCode()).isEqualTo(201);
        long userId = mapper.readTree(signup.body()).path("data").path("userId").asLong();
        assertThat(userId).isPositive();
        var login = post(client, "/api/v1/auth/login", Map.of("email", email, "password", password));
        assertThat(login.statusCode()).isEqualTo(200);
        String token = mapper.readTree(login.body()).path("data").path("accessToken").asText();
        assertThat(token).isNotBlank();
        return new TestAccount(userId, token);
    }

    private HttpResponse<String> post(HttpClient client, String path, Map<String, String> body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String authMessage(String token) throws Exception {
        return mapper.writeValueAsString(Map.of("type", "AUTH", "payload", Map.of("accessToken", token)));
    }

    private record TestAccount(long userId, String accessToken) {}

    private static final class Messages implements WebSocket.Listener {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder fragments = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                messages.add(fragments.toString());
                fragments.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            messages.add("CLOSE:" + statusCode + ":" + reason);
            return null;
        }

        private String next() throws InterruptedException {
            return messages.poll(5, TimeUnit.SECONDS);
        }
    }
}
