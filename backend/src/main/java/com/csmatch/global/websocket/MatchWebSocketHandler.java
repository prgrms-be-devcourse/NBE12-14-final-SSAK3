package com.csmatch.global.websocket;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.OptionalLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

public class MatchWebSocketHandler extends TextWebSocketHandler {
    private final ObjectMapper mapper;
    private final AccessTokenVerifier verifier;
    private final WebSocketConnections connections;
    private final Clock clock;

    public MatchWebSocketHandler(ObjectMapper mapper, AccessTokenVerifier verifier,
            WebSocketConnections connections, Clock clock) {
        this.mapper = mapper;
        this.verifier = verifier;
        this.connections = connections;
        this.clock = clock;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        connections.open(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        JsonNode envelope;
        try {
            envelope = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(message.getPayload());
        } catch (IOException exception) {
            reject(session, "INVALID_MESSAGE");
            return;
        }
        if (envelope == null || !envelope.isObject() || !envelope.path("type").isTextual()
                || !envelope.path("payload").isObject()) {
            reject(session, "INVALID_MESSAGE");
            return;
        }
        String type = envelope.get("type").textValue();
        JsonNode payload = envelope.get("payload");
        if (!connections.isAuthenticated(session.getId())) {
            if (!"AUTH".equals(type)) {
                reject(session, "AUTH_REQUIRED");
                return;
            }
            authenticate(session, payload);
            return;
        }
        if ("PING".equals(type) && payload.isEmpty()) {
            if (connections.ping(session.getId())) {
                connections.send(session.getId(), "PONG", Map.of("serverTime", clock.millis()));
            } else {
                reject(session, "HEARTBEAT_TIMEOUT");
            }
        } else {
            reject(session, "UNSUPPORTED_MESSAGE");
        }
    }

    private void authenticate(WebSocketSession session, JsonNode payload) {
        JsonNode token = payload.path("accessToken");
        if (!token.isTextual() || token.textValue().isBlank() || token.textValue().length() > 8_192) {
            reject(session, "AUTH_FAILED");
            return;
        }
        OptionalLong userId;
        try {
            userId = verifier.verify(token.textValue());
        } catch (RuntimeException exception) {
            reject(session, "AUTH_FAILED");
            return;
        }
        if (userId == null || userId.isEmpty() || !connections.authenticate(session.getId(), userId.getAsLong())) {
            reject(session, "AUTH_FAILED");
            return;
        }
        connections.send(session.getId(), "AUTH_SUCCESS", Map.of(
                "userId", userId.getAsLong(), "serverTime", clock.millis(),
                "pingIntervalSec", 5, "heartbeatTimeoutSec", 15));
    }

    private void reject(WebSocketSession session, String code) {
        connections.disconnect(session.getId(), new CloseStatus(1008, code));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        reject(session, "TEXT_ONLY");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // 클라이언트가 제공한 close reason은 신뢰하거나 이벤트에 그대로 넣지 않는다.
        connections.disconnect(session.getId(), new CloseStatus(1000, "CONNECTION_CLOSED"));
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        connections.disconnect(session.getId(), CloseStatus.SERVER_ERROR);
    }

    @Scheduled(fixedDelay = 1000)
    public void closeExpiredConnections() {
        connections.closeExpired();
    }
}
