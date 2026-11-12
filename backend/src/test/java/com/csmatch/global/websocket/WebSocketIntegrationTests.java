package com.csmatch.global.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(WebSocketIntegrationTests.TestAuthentication.class)
class WebSocketIntegrationTests {
    @LocalServerPort
    private int port;

    @Test
    void realUpgradeAuthenticationAndPingWork() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var listener = new Messages();
            var socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws"), listener).get(5, TimeUnit.SECONDS);
            try {
                socket.sendText("{\"type\":\"AUTH\",\"payload\":{\"accessToken\":\"test-only\"}}", true).join();
                assertThat(listener.next()).contains("AUTH_SUCCESS").doesNotContain("test-only");
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

    @TestConfiguration(proxyBeanMethods = false)
    static class TestAuthentication {
        @Bean
        AccessTokenVerifier testVerifier() {
            return token -> "test-only".equals(token) ? OptionalLong.of(7) : OptionalLong.empty();
        }
    }

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
