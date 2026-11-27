package com.react.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.auth.entity.User;
import com.react.auth.repository.UserRepository;
import com.react.chat.dto.ChatBatchEvent;
import com.react.global.websocket.StompTestClient;
import com.react.room.entity.LectureRoom;
import com.react.room.repository.LectureRoomRepository;
import com.react.room.service.LectureParticipantService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.servlet.session.cookie.secure=false"
)
@ActiveProfiles("test")
class ChatRecoveryIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    ObjectMapper json;

    @Autowired
    UserRepository users;

    @Autowired
    LectureRoomRepository rooms;

    @Autowired
    LectureParticipantService participants;

    @MockitoSpyBean
    SimpMessagingTemplate broker;

    private StompTestClient client;
    private LectureRoom room;
    private String participantCookie;
    private long participantId;

    @BeforeEach
    void setUp() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).isEqualTo("react_test");
        Flyway.configure().dataSource(dataSource).load().migrate();

        User host = users.saveAndFlush(new User(
                UUID.randomUUID() + "@example.com", "hash", "강연자"));

        String joinCode = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();

        room = rooms.saveAndFlush(new LectureRoom(
                host, "채팅 장애 복구 테스트", "SCRUM-172", joinCode));

        jdbc.sql("""
                UPDATE lecture_rooms
                SET status = 'LIVE', started_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """).param(room.getId()).update();

        LectureParticipantService.JoinResult joined = participants.join(
                joinCode, "청중", UUID.randomUUID().toString(), Map.of());

        participantId = joined.response().participantId();
        participantCookie = "PARTICIPANT_SESSION_" + room.getId() + "=" + joined.rawToken();

        client = new StompTestClient(port);
    }

    @Test
    void 채팅_방송이_실패해도_기록을_복구하고_재전송시_중복_저장되지_않는다() throws Exception {
        String attempt = UUID.randomUUID().toString();
        String content = "방송에 실패해도 복구되어야 하는 메시지";
        String topic = chatTopic();

        CountDownLatch broadcastFailed = new CountDownLatch(1);
        AtomicBoolean failOnce = new AtomicBoolean(true);

        // 해당 강연방의 첫 채팅 배치 방송만 실패시킨다.
        // 다른 메시지와 이후 방송은 실제 구현으로 처리한다.
        doAnswer(invocation -> {
            if (failOnce.compareAndSet(true, false)) {
                broadcastFailed.countDown();
                throw new IllegalStateException("테스트용 채팅 방송 장애");
            }

            return invocation.callRealMethod();
        }).when(broker).convertAndSend(eq(topic), any(ChatBatchEvent.class));

        StompSession first = null;
        StompSession reconnected = null;

        try {
            first = client.connect(StompTestClient.ALLOWED_ORIGIN, participantCookie);

            BlockingQueue<Map<String, Object>> firstQueue = subscribe(first);

            send(first, attempt, content);

            assertThat(broadcastFailed.await(5, TimeUnit.SECONDS))
                    .as("채팅 배치 방송에서 실제로 예외가 발생해야 한다")
                    .isTrue();

            assertThat(StompTestClient.nextOfType(firstQueue, "CHAT_BATCH", 500)).isNull();

            long originalMessageId = awaitSavedMessage();

            assertThat(originalMessageId).isPositive();
            assertThat(savedCount()).isEqualTo(1);

            // 방송 실패 이후 연결을 종료한다.
            first.disconnect();
            first = null;

            JsonNode recovered = history();

            assertThat(recovered).hasSize(1);
            assertMessage(recovered.get(0), originalMessageId, content);

            // 다시 연결하고 동일한 전송 식별자로 재전송한다.
            reconnected = client.connect(StompTestClient.ALLOWED_ORIGIN, participantCookie);

            BlockingQueue<Map<String, Object>> queue = subscribe(reconnected);

            send(reconnected, attempt, content);

            Map<String, Object> event = StompTestClient.nextOfType(queue, "CHAT_BATCH", 5000);

            assertThat(event).isNotNull();

            JsonNode eventData = json.valueToTree(event.get("data"));
            JsonNode replayed = eventData.path("messages");

            assertThat(replayed).hasSize(1);

            JsonNode message = replayed.get(0);

            assertThat(message.path("attempt").asText()).isEqualTo(attempt);
            assertMessage(message, originalMessageId, content);

            // 실패한 최초 방송과 성공한 재전송 방송을 확인한다.
            verify(broker, times(2)).convertAndSend(eq(topic), any(ChatBatchEvent.class));

            assertThat(savedCount()).isEqualTo(1);

            JsonNode finalHistory = history();

            assertThat(finalHistory).hasSize(1);
            assertMessage(finalHistory.get(0), originalMessageId, content);
        } finally {
            disconnect(first);
            disconnect(reconnected);
        }
    }

    private BlockingQueue<Map<String, Object>> subscribe(StompSession session) throws InterruptedException {
        String topic = chatTopic();

        BlockingQueue<Map<String, Object>> queue = client.subscribe(session, topic);

        assertThat(StompTestClient.awaitBroadcast(queue,
                () -> broker.convertAndSend(topic, Map.of("type", "WARMUP"))))
                .isNotNull();

        return queue;
    }

    private void send(StompSession session, String attempt, String content) {
        StompHeaders headers = new StompHeaders();

        headers.setDestination("/app/rooms/" + room.getId() + "/chat");
        headers.set("X-Chat-Attempt", attempt);

        session.send(headers, Map.of("content", content));
    }

    private JsonNode history() throws Exception {
        HttpClient http = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/rooms/" + room.getId() + "/messages"))
                .header("Cookie", participantCookie)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode messages = json.readTree(response.body()).path("data").path("messages");

        assertThat(messages.isArray()).isTrue();

        return messages;
    }

    private void assertMessage(JsonNode message, long messageId, String content) {
        assertThat(message.path("messageId").asLong()).isEqualTo(messageId);
        assertThat(message.path("content").asText()).isEqualTo(content);
        assertThat(message.path("sender").path("type").asText()).isEqualTo("PARTICIPANT");
        assertThat(message.path("sender").path("id").asLong()).isEqualTo(participantId);
        assertThat(message.path("receivedAt").asText()).isNotBlank();
    }

    private long awaitSavedMessage() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (System.nanoTime() < deadline) {
            List<Long> ids = jdbc.sql("SELECT id FROM chat_messages WHERE lecture_room_id = ?")
                    .param(room.getId()).query(Long.class).list();

            if (!ids.isEmpty()) {
                assertThat(ids).hasSize(1);
                return ids.getFirst();
            }

            Thread.sleep(50);
        }

        throw new AssertionError("5초 이내에 채팅이 저장되지 않았습니다.");
    }

    private long savedCount() {
        return jdbc.sql("SELECT COUNT(*) FROM chat_messages WHERE lecture_room_id = ?")
                .param(room.getId()).query(Long.class).single();
    }

    private String chatTopic() {
        return "/topic/rooms/" + room.getId() + "/chat";
    }

    private static void disconnect(StompSession session) {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }
}