package com.react.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.auth.entity.User;
import com.react.auth.repository.UserRepository;
import com.react.chat.dto.ChatBatchEvent;
import com.react.chat.service.ChatBroadcastBatcher;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import javax.sql.DataSource;
import java.lang.reflect.Type;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.servlet.session.cookie.secure=false"
)
@ActiveProfiles("test")
class ChatBatchConcurrencyIntegrationTest {

    private static final String PASSWORD = "test-password-1!";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ChatBroadcastBatcher batcher;

    @Autowired
    private SimpMessagingTemplate broker;

    private Browser hostBrowser;
    private long roomId;
    private String joinCode;

    @BeforeEach
    void setUp() throws Exception {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single())
                .endsWith("_test");

        Flyway.configure().dataSource(dataSource).load().migrate();

        String email = "batch-" + UUID.randomUUID() + "@example.com";

        users.saveAndFlush(new User(email, passwordEncoder.encode(PASSWORD), "강연자"));

        hostBrowser = newBrowser();

        post(hostBrowser, "/api/v1/auth/login",
                Map.of("email", email, "password", PASSWORD), 200);

        HttpResponse<String> response = post(hostBrowser, "/api/v1/rooms",
                Map.of("title", "채팅 동시성 통합 테스트", "description", "실사용 검증"), 201);

        JsonNode room = json.readTree(response.body()).path("data");

        roomId = room.path("roomId").asLong();
        joinCode = room.path("joinCode").asText();

        assertThat(roomId).isPositive();
        assertThat(joinCode).isNotBlank();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 빈_대기열을_정리하는_순간_청중이_보낸_채팅도_모두에게_전달된다() throws Exception {
        Browser audienceABrowser = newBrowser();
        Browser audienceBBrowser = newBrowser();

        String audienceACookie = join(audienceABrowser, "청중A");
        String audienceBCookie = join(audienceBBrowser, "청중B");

        post(hostBrowser, roomPath() + "/start", Map.of(), 200);

        WebSocketStompClient stompClient =
                new WebSocketStompClient(new StandardWebSocketClient());

        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        CountDownLatch emptyChecked = new CountDownLatch(1);
        CountDownLatch messageAdded = new CountDownLatch(1);
        CountDownLatch allowCleanup = new CountDownLatch(1);

        AtomicBoolean firstEmptyCheck = new AtomicBoolean(true);
        AtomicBoolean cleanupTimedOut = new AtomicBoolean(false);

        StompSession host = null;
        StompSession audienceA = null;
        StompSession audienceB = null;

        try {
            host = connect(stompClient, hostBrowser, null);
            audienceA = connect(stompClient, audienceABrowser, audienceACookie);
            audienceB = connect(stompClient, audienceBBrowser, audienceBCookie);

            BlockingQueue<JsonNode> hostMessages = subscribe(host);
            BlockingQueue<JsonNode> audienceAMessages = subscribe(audienceA);
            BlockingQueue<JsonNode> audienceBMessages = subscribe(audienceB);

            // 청중 A가 채팅을 보내면 강연자와 모든 청중에게 전달된다.
            String firstContent = "안녕하세요";
            String firstAttempt = send(audienceA, firstContent);

            long firstMessageId = receive(hostMessages, firstContent, firstAttempt);

            assertThat(receive(audienceAMessages, firstContent, firstAttempt))
                    .isEqualTo(firstMessageId);

            assertThat(receive(audienceBMessages, firstContent, firstAttempt))
                    .isEqualTo(firstMessageId);

            Map<Long, Queue<ChatBatchEvent.Message>> pending =
                    (Map<Long, Queue<ChatBatchEvent.Message>>)
                            ReflectionTestUtils.getField(batcher, "pending");

            assertThat(pending).isNotNull();

            // 빈 대기열 확인과 제거 사이에 새로운 채팅이 들어오는 상황을 재현한다.
            Queue<ChatBatchEvent.Message> controlledQueue = new ConcurrentLinkedQueue<>() {
                @Override
                public ChatBatchEvent.Message poll() {
                    ChatBatchEvent.Message next = super.poll();

                    if (next == null && firstEmptyCheck.compareAndSet(true, false)) {
                        emptyChecked.countDown();

                        try {
                            if (!allowCleanup.await(15, TimeUnit.SECONDS)) {
                                cleanupTimedOut.set(true);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            cleanupTimedOut.set(true);
                        }
                    }

                    return next;
                }

                @Override
                public boolean add(ChatBatchEvent.Message message) {
                    boolean added = super.add(message);

                    messageAdded.countDown();

                    return added;
                }
            };

            pending.put(roomId, controlledQueue);

            // 실제 배치 스레드가 빈 대기열을 확인할 때까지 기다린다.
            assertThat(emptyChecked.await(5, TimeUnit.SECONDS))
                    .as("배치 스레드가 빈 대기열을 확인해야 한다")
                    .isTrue();

            // 대기열을 제거하려는 순간 청중 B가 질문을 보낸다.
            String secondContent = "트랜잭션에 대해 질문 있습니다";
            String secondAttempt = send(audienceB, secondContent);

            assertThat(messageAdded.await(5, TimeUnit.SECONDS))
                    .as("청중이 보낸 채팅이 대기열에 추가되어야 한다")
                    .isTrue();

            // 배치 전송 전에도 DB 저장은 완료되어 있어야 한다.
            long savedMessageId = jdbc.sql("""
                            SELECT id
                            FROM chat_messages
                            WHERE lecture_room_id = ? AND content = ?
                            """)
                    .params(roomId, secondContent)
                    .query(Long.class)
                    .single();

            assertThat(savedMessageId).isPositive();

            // 중단했던 대기열 정리 작업을 재개한다.
            allowCleanup.countDown();

            // 강연자와 두 청중 모두 같은 채팅을 받아야 한다.
            assertThat(receive(hostMessages, secondContent, secondAttempt))
                    .isEqualTo(savedMessageId);

            assertThat(receive(audienceAMessages, secondContent, secondAttempt))
                    .isEqualTo(savedMessageId);

            assertThat(receive(audienceBMessages, secondContent, secondAttempt))
                    .isEqualTo(savedMessageId);

            assertThat(cleanupTimedOut.get()).isFalse();

            // 두 번의 채팅 전송으로 정확히 두 건이 저장되어야 한다.
            long count = jdbc.sql("""
                            SELECT COUNT(*)
                            FROM chat_messages
                            WHERE lecture_room_id = ?
                            """)
                    .param(roomId)
                    .query(Long.class)
                    .single();

            assertThat(count).isEqualTo(2L);

        } finally {
            allowCleanup.countDown();

            disconnect(audienceB);
            disconnect(audienceA);
            disconnect(host);

            stompClient.stop();
        }
    }

    private Browser newBrowser() {
        CookieManager cookies = new CookieManager();

        HttpClient http = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        return new Browser(http, cookies);
    }

    private String join(Browser browser, String nickname) throws Exception {
        HttpResponse<String> response = post(
                browser,
                "/api/v1/public/rooms/" + joinCode + "/participants",
                Map.of("nickname", nickname),
                201
        );

        return response.headers().allValues("Set-Cookie").stream()
                .map(value -> value.split(";", 2)[0])
                .filter(value -> value.startsWith("PARTICIPANT_SESSION_" + roomId + "="))
                .findFirst()
                .orElseThrow();
    }

    private StompSession connect(
            WebSocketStompClient client,
            Browser browser,
            String participantCookie
    ) throws Exception {
        String csrfToken = csrf(browser);

        String hostCookie = browser.cookies().getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("HOST_SESSION"))
                .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                .findFirst()
                .orElseThrow();

        String cookies = participantCookie == null
                ? hostCookie
                : hostCookie + "; " + participantCookie;

        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.setOrigin("http://localhost:5173");
        handshakeHeaders.add("Cookie", cookies);

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("X-CSRF-TOKEN", csrfToken);

        return client.connectAsync(
                "ws://localhost:" + port + "/ws",
                handshakeHeaders,
                connectHeaders,
                new StompSessionHandlerAdapter() {
                }
        ).get(5, TimeUnit.SECONDS);
    }

    private BlockingQueue<JsonNode> subscribe(StompSession session)
            throws InterruptedException {
        String topic = "/topic/rooms/" + roomId + "/chat";

        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();

        session.subscribe(topic, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add(json.valueToTree(payload));
            }
        });

        // 실제 WebSocket 구독이 완료되었는지 확인한다.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (System.nanoTime() < deadline) {
            broker.convertAndSend(topic, Map.of("type", "WARMUP"));

            JsonNode event = received.poll(100, TimeUnit.MILLISECONDS);

            if (event != null && event.path("type").asText().equals("WARMUP")) {
                return received;
            }
        }

        throw new AssertionError("채팅 WebSocket 구독에 실패했다");
    }

    private String send(StompSession session, String content) {
        String attempt = UUID.randomUUID().toString();

        StompHeaders headers = new StompHeaders();
        headers.setDestination("/app/rooms/" + roomId + "/chat");
        headers.set("X-Chat-Attempt", attempt);

        session.send(headers, Map.of("content", content));

        return attempt;
    }

    private long receive(
            BlockingQueue<JsonNode> received,
            String content,
            String attempt
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (System.nanoTime() < deadline) {
            JsonNode event = received.poll(100, TimeUnit.MILLISECONDS);

            if (event == null || !event.path("type").asText().equals("CHAT_BATCH")) {
                continue;
            }

            JsonNode messages = event.path("data").path("messages");

            for (JsonNode message : messages) {
                if (!message.path("attempt").asText().equals(attempt)) {
                    continue;
                }

                assertThat(message.path("roomId").asLong()).isEqualTo(roomId);
                assertThat(message.path("content").asText()).isEqualTo(content);
                assertThat(message.path("messageId").asLong()).isPositive();

                return message.path("messageId").asLong();
            }
        }

        throw new AssertionError("실시간 채팅 수신 실패: " + content);
    }

    private String csrf(Browser browser) throws Exception {
        HttpResponse<String> response = browser.http().send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/auth/csrf"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertThat(response.statusCode()).isEqualTo(200);

        String token = json.readTree(response.body()).path("data").path("token").asText();

        assertThat(token).isNotBlank();

        return token;
    }

    private HttpResponse<String> post(
            Browser browser,
            String path,
            Map<String, Object> body,
            int expectedStatus
    ) throws Exception {
        String token = csrf(browser);

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", token);

        if (path.endsWith("/participants")) {
            request.header("Idempotency-Key", UUID.randomUUID().toString());
        }

        HttpResponse<String> response = browser.http().send(
                request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertThat(response.statusCode())
                .as(path + ": " + response.body())
                .isEqualTo(expectedStatus);

        return response;
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private String roomPath() {
        return "/api/v1/rooms/" + roomId;
    }

    private void disconnect(StompSession session) {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }

    private record Browser(HttpClient http, CookieManager cookies) {
    }
}