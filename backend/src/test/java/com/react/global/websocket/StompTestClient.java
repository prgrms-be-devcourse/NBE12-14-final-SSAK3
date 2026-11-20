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

    /** 구독하고, 받은 메시지를 도착 순서대로 큐에 쌓는다. */
    public BlockingQueue<Map<String, Object>> subscribe(StompSession session, String destination) {
        BlockingQueue<Map<String, Object>> received = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((Map<String, Object>) payload);
            }
        });
        return received;
    }

    /** 메시지가 올 때까지 기다린다. 시간 안에 안 오면 null. */
    public static Map<String, Object> next(BlockingQueue<Map<String, Object>> received) throws InterruptedException {
        return received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
