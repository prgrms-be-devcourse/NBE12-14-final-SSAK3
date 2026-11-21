package com.react.global.websocket;

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
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * STOMP 테스트용 클라이언트. 휴대폰 대신 코드로 연결하고 구독하고 메시지를 받는다.
 * 쿠키는 브라우저가 해 주는 것처럼 연결 요청(핸드셰이크) 헤더에 직접 실어 보낸다.
 */
public final class StompTestClient {
    public static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final long TIMEOUT_SECONDS = 5;

    private final WebSocketStompClient client;
    private final String url;

    public StompTestClient(int port) {
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        url = "ws://localhost:" + port + "/ws";
    }

    /** 연결에 성공하면 세션을 돌려주고, 서버가 거절하면 예외를 던진다. */
    public StompSession connect(String origin, String... cookies) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin(origin);
        for (String cookie : cookies) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        return client.connectAsync(url, headers, new StompSessionHandlerAdapter() { })
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 구독하고, 받은 메시지를 도착 순서대로 큐에 쌓는다.
     * 서버의 내장 브로커는 구독 완료를 알려 주지 않으므로(영수증 미지원), 구독이 등록된 시점은
     * awaitBroadcast 로 방송을 반복해 보내 보며 확인한다.
     */
    public BlockingQueue<Map<String, Object>> subscribe(StompSession session, String destination) {
        return subscribe(session, destination, (headers, body) -> {});
    }

    public BlockingQueue<Map<String, Object>> subscribe(StompSession session, String destination,
            BiConsumer<StompHeaders, Map<String, Object>> inspect) {
        BlockingQueue<Map<String, Object>> received = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                Map<String, Object> body = (Map<String, Object>) payload;
                inspect.accept(headers, body);
                received.add(body);
            }
        });
        return received;
    }

    /** 메시지가 올 때까지 기다린다. 시간 안에 안 오면 null. */
    public static Map<String, Object> next(BlockingQueue<Map<String, Object>> received) throws InterruptedException {
        return received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 구독이 등록되어 방송이 닿을 때까지 broadcast 를 짧은 간격으로 반복하며 첫 메시지를 기다린다.
     * 시간 안에 못 받으면 null. 중복 방송은 큐에 더 쌓일 수 있으므로, 이후 검사는 필요하면 큐를 비우고 한다.
     */
    public static Map<String, Object> awaitBroadcast(BlockingQueue<Map<String, Object>> received, Runnable broadcast)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            broadcast.run();
            Map<String, Object> message = received.poll(100, TimeUnit.MILLISECONDS);
            if (message != null) {
                return message;
            }
        }
        return null;
    }

    /**
     * 큐에서 지정한 type 의 메시지가 올 때까지 기다린다. 다른 type(예: 워밍업)은 건너뛴다.
     * 시간 안에 못 받으면 null. "오면 안 되는 메시지"를 확인할 때는 짧은 제한시간을 준다.
     */
    public static Map<String, Object> nextOfType(BlockingQueue<Map<String, Object>> received, String type,
            long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMillis <= 0) {
                return null;
            }
            Map<String, Object> message = received.poll(remainingMillis, TimeUnit.MILLISECONDS);
            if (message == null) {
                return null;
            }
            if (type.equals(message.get("type"))) {
                return message;
            }
        }
    }

    /** 서버가 연결을 끊을 때까지 기다린다. 구독이 거절되면 서버가 ERROR 를 보내고 연결을 닫는다. */
    public static boolean awaitClosed(StompSession session) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (session.isConnected() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        return !session.isConnected();
    }
}
