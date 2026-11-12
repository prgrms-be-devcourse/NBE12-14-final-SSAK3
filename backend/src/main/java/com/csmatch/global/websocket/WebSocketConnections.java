package com.csmatch.global.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

public class WebSocketConnections {
    private static final long AUTH_TIMEOUT = Duration.ofSeconds(10).toNanos();
    private static final long HEARTBEAT_TIMEOUT = Duration.ofSeconds(15).toNanos();
    private final Map<String, Connection> sessions = new HashMap<>();
    private final Map<Long, Connection> users = new HashMap<>();
    private final Object lock = new Object();
    private final ObjectMapper mapper;
    private final ApplicationEventPublisher events;
    private final LongSupplier ticker;

    public WebSocketConnections(ObjectMapper mapper, ApplicationEventPublisher events, LongSupplier ticker) {
        this.mapper = mapper;
        this.events = events;
        this.ticker = ticker;
    }

    public void open(WebSocketSession session) {
        session.setTextMessageSizeLimit(16 * 1024);
        session.setBinaryMessageSizeLimit(16 * 1024);
        synchronized (lock) {
            sessions.put(session.getId(), new Connection(session, ticker.getAsLong()));
        }
    }

    public boolean isAuthenticated(String sessionId) {
        synchronized (lock) {
            Connection connection = sessions.get(sessionId);
            return connection != null && connection.userId != null;
        }
    }

    public boolean authenticate(String sessionId, long userId) {
        Connection previous;
        synchronized (lock) {
            Connection connection = sessions.get(sessionId);
            if (connection == null || connection.userId != null || userId <= 0
                    || ticker.getAsLong() - connection.lastSeen >= AUTH_TIMEOUT) {
                return false;
            }
            connection.userId = userId;
            connection.lastSeen = ticker.getAsLong();
            previous = users.put(userId, connection);
            if (previous != null) {
                sessions.remove(previous.session.getId());
            }
        }
        if (previous != null) {
            closeTransport(previous, new CloseStatus(4002, "CONNECTION_REPLACED"));
        }
        return true;
    }

    public boolean ping(String sessionId) {
        synchronized (lock) {
            Connection connection = sessions.get(sessionId);
            if (connection == null || connection.userId == null
                    || ticker.getAsLong() - connection.lastSeen >= HEARTBEAT_TIMEOUT) {
                return false;
            }
            connection.lastSeen = ticker.getAsLong();
            return true;
        }
    }

    public void send(String sessionId, String type, Object payload) {
        Connection connection;
        synchronized (lock) {
            connection = sessions.get(sessionId);
        }
        if (connection != null) {
            send(connection, type, payload);
        }
    }

    /** 서버가 판단한 사용자에게만 전송한다. 상대 사용자 조회는 경기 모듈의 책임이다. */
    public void sendToUser(long userId, String type, Object payload) {
        Connection connection;
        synchronized (lock) {
            connection = users.get(userId);
        }
        if (connection != null) {
            send(connection, type, payload);
        }
    }

    private void send(Connection connection, String type, Object payload) {
        try {
            connection.session.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of(
                    "type", type, "payload", payload))));
        } catch (IOException | RuntimeException exception) {
            // 메시지 본문과 예외 메시지에는 토큰이 포함될 수 있으므로 기록하지 않는다.
            disconnect(connection.session.getId(), CloseStatus.SERVER_ERROR);
        }
    }

    public void disconnect(String sessionId, CloseStatus status) {
        Connection connection;
        boolean wasCurrent;
        synchronized (lock) {
            connection = sessions.remove(sessionId);
            if (connection == null) {
                return;
            }
            wasCurrent = connection.userId != null && users.remove(connection.userId, connection);
        }
        closeTransport(connection, status);
        if (wasCurrent) {
            events.publishEvent(new WebSocketDisconnected(connection.userId, sessionId, status.getReason()));
        }
    }

    public void closeExpired() {
        var expired = new ArrayList<Connection>();
        synchronized (lock) {
            long now = ticker.getAsLong();
            var iterator = sessions.values().iterator();
            while (iterator.hasNext()) {
                Connection connection = iterator.next();
                long timeout = connection.userId == null ? AUTH_TIMEOUT : HEARTBEAT_TIMEOUT;
                if (now - connection.lastSeen >= timeout) {
                    iterator.remove();
                    if (connection.userId != null) {
                        users.remove(connection.userId, connection);
                    }
                    expired.add(connection);
                }
            }
        }
        for (Connection connection : expired) {
            String reason = connection.userId == null ? "AUTH_TIMEOUT" : "HEARTBEAT_TIMEOUT";
            closeTransport(connection, new CloseStatus(4000, reason));
            if (connection.userId != null) {
                events.publishEvent(new WebSocketDisconnected(connection.userId, connection.session.getId(), reason));
            }
        }
    }

    private void closeTransport(Connection connection, CloseStatus status) {
        try {
            connection.session.close(status);
        } catch (IOException | RuntimeException exception) {
            // 전송 계층이 이미 실패해도 위에서 세션 등록은 제거된다.
        }
    }

    private static final class Connection {
        private final WebSocketSession session;
        private Long userId;
        private long lastSeen;

        private Connection(WebSocketSession session, long now) {
            this.session = new ConcurrentWebSocketSessionDecorator(session, 5_000, 64 * 1024);
            this.lastSeen = now;
        }
    }
}
