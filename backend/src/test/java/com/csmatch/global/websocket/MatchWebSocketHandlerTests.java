package com.csmatch.global.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatchWebSocketHandlerTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong nanos = new AtomicLong();
    private final List<Object> events = new CopyOnWriteArrayList<>();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC);
    private AccessTokenVerifier verifier;
    private WebSocketConnections connections;
    private MatchWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        verifier = mock(AccessTokenVerifier.class);
        when(verifier.verify("valid")).thenReturn(OptionalLong.of(7));
        when(verifier.verify("invalid")).thenReturn(OptionalLong.empty());
        connections = new WebSocketConnections(mapper, events::add, nanos::get);
        handler = new MatchWebSocketHandler(mapper, verifier, connections, clock);
    }

    @Test
    void authenticationUsesVerifiedIdentityAndPingReturnsServerTime() throws Exception {
        var session = session("one");
        var sent = capture(session);
        message(session, """
                {"type":"AUTH","payload":{"accessToken":"valid","userId":999}}
                """);
        assertThat(mapper.readTree(sent.getFirst()).path("payload").path("userId").asLong()).isEqualTo(7);
        nanos.addAndGet(Duration.ofSeconds(5).toNanos());
        message(session, "{\"type\":\"PING\",\"payload\":{}}");
        assertThat(mapper.readTree(sent.getLast()).path("type").asText()).isEqualTo("PONG");
        assertThat(mapper.readTree(sent.getLast()).path("payload").path("serverTime").asLong()).isEqualTo(clock.millis());
        assertThat(sent).noneMatch(text -> text.contains("valid"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{", "{}", "{\"type\":\"AUTH\",\"payload\":null}",
            "{\"type\":\"AUTH\",\"payload\":{}}", "{\"type\":\"AUTH\",\"payload\":{\"accessToken\":\" \"}}",
            "{\"type\":\"PING\",\"payload\":{}}", "{\"type\":\"ANSWER\",\"payload\":{}}",
            "{\"type\":\"AUTH\",\"payload\":{\"accessToken\":\"valid\"}} {}"})
    void invalidOrUnauthenticatedMessagesAreClosed(String input) throws Exception {
        var session = session("one");
        message(session, input);
        verify(session).close(any(CloseStatus.class));
        verify(session, never()).sendMessage(any());
        assertThat(connections.isAuthenticated("one")).isFalse();
        assertThat(events).isEmpty();
    }

    @Test
    void invalidTokenAndVerifierFailureDoNotLeakDetails() throws Exception {
        var invalid = session("invalid");
        auth(invalid, "invalid");
        verify(invalid).close(new CloseStatus(1008, "AUTH_FAILED"));
        var broken = session("broken");
        when(verifier.verify("secret")).thenThrow(new IllegalArgumentException("secret"));
        auth(broken, "secret");
        verify(broken).close(new CloseStatus(1008, "AUTH_FAILED"));
        verify(broken, never()).sendMessage(any());
    }

    @Test
    void unauthenticatedConnectionExpiresAtTenSeconds() throws Exception {
        var session = session("one");
        nanos.set(Duration.ofSeconds(10).toNanos() - 1);
        connections.closeExpired();
        verify(session, never()).close(any());
        nanos.incrementAndGet();
        connections.closeExpired();
        verify(session).close(new CloseStatus(4000, "AUTH_TIMEOUT"));
    }

    @Test
    void authenticationCannotBeatTimeoutByArrivingBeforeNextSweep() throws Exception {
        var session = session("one");
        nanos.set(Duration.ofSeconds(10).toNanos());
        auth(session, "valid");
        assertThat(connections.isAuthenticated("one")).isFalse();
        verify(session).close(new CloseStatus(1008, "AUTH_FAILED"));
    }

    @Test
    void slowVerificationCannotAuthenticateAfterDeadline() throws Exception {
        var session = session("one");
        when(verifier.verify("slow")).thenAnswer(invocation -> {
            nanos.set(Duration.ofSeconds(10).toNanos());
            return OptionalLong.of(7);
        });
        auth(session, "slow");
        assertThat(connections.isAuthenticated("one")).isFalse();
    }

    @Test
    void onlyValidPingExtendsHeartbeatAndDisconnectIsEmittedOnce() throws Exception {
        var session = session("one");
        auth(session, "valid");
        nanos.set(Duration.ofSeconds(5).toNanos());
        message(session, "{\"type\":\"PING\",\"payload\":{}}");
        nanos.set(Duration.ofSeconds(20).toNanos() - 1);
        connections.closeExpired();
        verify(session, never()).close(any());
        nanos.incrementAndGet();
        connections.closeExpired();
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        connections.closeExpired();
        assertThat(events).containsExactly(new WebSocketDisconnected(7, "one", "HEARTBEAT_TIMEOUT"));
    }

    @Test
    void latePingCannotReviveExpiredConnection() throws Exception {
        var session = session("one");
        var sent = capture(session);
        auth(session, "valid");
        nanos.set(Duration.ofSeconds(15).toNanos());
        message(session, "{\"type\":\"PING\",\"payload\":{}}");
        assertThat(sent).hasSize(1);
        assertThat(connections.isAuthenticated("one")).isFalse();
    }

    @Test
    void replacementAndOldCloseDoNotDisconnectNewSession() throws Exception {
        var old = session("old");
        auth(old, "valid");
        var next = session("next");
        var sent = capture(next);
        auth(next, "valid");
        verify(old).close(new CloseStatus(4002, "CONNECTION_REPLACED"));
        handler.afterConnectionClosed(old, CloseStatus.NORMAL);
        handler.handleTransportError(old, new IOException());
        connections.sendToUser(7, "TEST", java.util.Map.of());
        assertThat(connections.isAuthenticated("next")).isTrue();
        assertThat(mapper.readTree(sent.getLast()).path("type").asText()).isEqualTo("TEST");
        assertThat(events).isEmpty();
    }

    @Test
    void failedReplacementAuthenticationKeepsOriginalSession() throws Exception {
        var old = session("old");
        auth(old, "valid");
        auth(session("next"), "invalid");
        assertThat(connections.isAuthenticated("old")).isTrue();
        verify(old, never()).close(any());
    }

    @Test
    void sendFailureRemovesSessionAndNotifiesOnce() throws Exception {
        var session = session("one");
        auth(session, "valid");
        doThrow(new IOException()).when(session).sendMessage(any());
        connections.sendToUser(7, "TEST", java.util.Map.of());
        handler.handleTransportError(session, new IOException());
        assertThat(connections.isAuthenticated("one")).isFalse();
        assertThat(events).hasSize(1);
    }

    @Test
    void authenticatedClientCannotSwitchIdentity() throws Exception {
        var session = session("one");
        auth(session, "valid");
        auth(session, "valid");
        verify(session).close(new CloseStatus(1008, "UNSUPPORTED_MESSAGE"));
    }

    @Test
    void concurrentWritesAreSerializedWithoutLosingMessages() throws Exception {
        var session = session("one");
        auth(session, "valid");
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var writes = new AtomicInteger();
        var active = new AtomicInteger();
        var maxActive = new AtomicInteger();
        doAnswer(invocation -> {
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                if (writes.incrementAndGet() == 1) {
                    firstEntered.countDown();
                    assertThat(releaseFirst.await(3, TimeUnit.SECONDS)).isTrue();
                }
            } finally {
                active.decrementAndGet();
            }
            return null;
        }).when(session).sendMessage(any());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> connections.sendToUser(7, "ONE", java.util.Map.of()));
            try {
                assertThat(firstEntered.await(3, TimeUnit.SECONDS)).isTrue();
                executor.submit(() -> connections.sendToUser(7, "TWO", java.util.Map.of())).get(3, TimeUnit.SECONDS);
            } finally {
                releaseFirst.countDown();
            }
            first.get(3, TimeUnit.SECONDS);
        }
        assertThat(writes.get()).isEqualTo(2);
        assertThat(maxActive.get()).isEqualTo(1);
    }

    private WebSocketSession session(String id) {
        var session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(session);
        return session;
    }

    private List<String> capture(WebSocketSession session) throws IOException {
        List<String> sent = new ArrayList<>();
        doAnswer(invocation -> {
            sent.add(((TextMessage) invocation.getArgument(0)).getPayload());
            return null;
        }).when(session).sendMessage(any());
        return sent;
    }

    private void auth(WebSocketSession session, String token) throws Exception {
        message(session, mapper.writeValueAsString(java.util.Map.of("type", "AUTH",
                "payload", java.util.Map.of("accessToken", token))));
    }

    private void message(WebSocketSession session, String text) throws Exception {
        handler.handleMessage(session, new TextMessage(text));
    }
}
