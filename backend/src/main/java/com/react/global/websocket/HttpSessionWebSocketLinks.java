package com.react.global.websocket;

import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

/**
 * 하나의 HTTP 세션과 연결된 WebSocket 목록을 관리한다.
 *
 * HTTP 세션이 무효화되거나 로그아웃하면 연결을 종료한다.
 * 청중 인증이 만료되는 시각에도 해당 WebSocket을 종료할 수 있다.
 */
public class HttpSessionWebSocketLinks
        implements HttpSessionBindingListener {

    public static final String SESSION_ATTRIBUTE =
            HttpSessionWebSocketLinks.class.getName();

    public static final String HANDSHAKE_ATTRIBUTE =
            "stompWebSocketLinks";

    private final Object lock = new Object();

    private final Map<String, WebSocketSession> sockets =
            new HashMap<>();

    private final Map<String, ScheduledFuture<?>> expiryTasks =
            new HashMap<>();

    private boolean revoked = false;

    /**
     * 핸드셰이크가 완료된 소켓을 등록한다.
     * 이미 로그아웃 또는 세션 만료가 발생했다면 등록하지 않는다.
     */
    public boolean register(WebSocketSession socket) {
        synchronized (lock) {
            if (revoked) {
                return false;
            }

            sockets.put(socket.getId(), socket);
            return true;
        }
    }

    /**
     * 현재 HTTP 세션에 속한 유효한 소켓인지 확인한다.
     */
    public boolean isActive(String socketId) {
        if (socketId == null) {
            return false;
        }

        synchronized (lock) {
            WebSocketSession socket = sockets.get(socketId);

            return !revoked
                    && socket != null
                    && socket.isOpen();
        }
    }

    /**
     * 청중 인증 만료 시각에 소켓을 자동 종료한다.
     */
    public void expireAt(
            String socketId,
            Instant expiresAt,
            TaskScheduler scheduler
    ) {
        synchronized (lock) {
            if (revoked
                    || !sockets.containsKey(socketId)
                    || expiryTasks.containsKey(socketId)) {
                return;
            }

            ScheduledFuture<?> future = scheduler.schedule(
                    () -> closeSocket(socketId),
                    expiresAt
            );

            if (future == null) {
                throw new IllegalStateException(
                        "WebSocket 만료 작업을 등록할 수 없습니다."
                );
            }

            expiryTasks.put(socketId, future);
        }
    }

    /**
     * 특정 WebSocket 연결만 종료한다.
     */
    public void closeSocket(String socketId) {
        WebSocketSession socket;
        ScheduledFuture<?> task;

        synchronized (lock) {
            socket = sockets.remove(socketId);
            task = expiryTasks.remove(socketId);
        }

        if (task != null) {
            task.cancel(false);
        }

        close(socket);
    }

    /**
     * 클라이언트가 정상적으로 연결을 종료한 경우 정리한다.
     */
    public void unregister(String socketId) {
        ScheduledFuture<?> task;

        synchronized (lock) {
            sockets.remove(socketId);
            task = expiryTasks.remove(socketId);
        }

        if (task != null) {
            task.cancel(false);
        }
    }

    /**
     * 로그아웃, 재로그인 또는 HTTP 세션 만료 시
     * 기존 WebSocket을 모두 종료한다.
     */
    public void closeAll() {
        List<WebSocketSession> toClose;
        List<ScheduledFuture<?>> toCancel;

        synchronized (lock) {
            if (revoked) {
                return;
            }

            revoked = true;

            toClose = new ArrayList<>(sockets.values());
            toCancel = new ArrayList<>(expiryTasks.values());

            sockets.clear();
            expiryTasks.clear();
        }

        for (ScheduledFuture<?> task : toCancel) {
            task.cancel(false);
        }

        for (WebSocketSession socket : toClose) {
            close(socket);
        }
    }

    /**
     * HTTP 세션에서 이 객체가 제거되거나
     * HTTP 세션 자체가 무효화되면 자동 호출된다.
     */
    @Override
    public void valueUnbound(HttpSessionBindingEvent event) {
        closeAll();
    }

    private void close(WebSocketSession socket) {
        if (socket == null) {
            return;
        }

        try {
            if (socket.isOpen()) {
                socket.close(CloseStatus.POLICY_VIOLATION);
            }
        } catch (IOException exception) {
            // 연결이 이미 끊어진 경우에도 서버의 관리 목록은 정리한다.
        }
    }
}
