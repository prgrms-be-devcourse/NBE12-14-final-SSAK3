package com.react.global.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.auth.entity.User;
import com.react.auth.repository.UserRepository;
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
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 채팅 전송: 작성자는 쿠키·로그인으로 서버가 정하고, 본문에서는 content 만 읽어 저장한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.servlet.session.cookie.secure=false")
@ActiveProfiles("test")
class StompChatSendTest {
    private static final String PASSWORD = "test-password-1!";

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired UserRepository users;
    @Autowired LectureRoomRepository rooms;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired LectureParticipantService participants;
    @Autowired org.springframework.messaging.simp.SimpMessagingTemplate broker;

    private StompTestClient client;
    private String hostEmail;
    private User host;
    private LectureRoom room;

    @BeforeEach
    void prepare() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        client = new StompTestClient(port);
        hostEmail = "host-" + UUID.randomUUID() + "@example.com";
        host = users.saveAndFlush(new User(hostEmail, passwordEncoder.encode(PASSWORD), "강연자"));
        String code = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        room = rooms.saveAndFlush(new LectureRoom(host, "전송 테스트", "테스트 강연", code));
        jdbc.sql("UPDATE lecture_rooms SET status = 'LIVE' WHERE id = ?").param(room.getId()).update();
    }

    @Test
    void 청중이_보낸_메시지는_그_청중이_작성자로_저장된다() throws Exception {
        var joined = participants.join(room.getJoinCode(), "청중A", UUID.randomUUID().toString(), Map.of());
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN,
                "PARTICIPANT_SESSION_" + room.getId() + "=" + joined.rawToken());

        session.send(chatDestination(), Map.of("content", "Redis 가 궁금해요"));

        Map<String, Object> row = awaitSavedMessage();
        assertThat(row.get("content")).isEqualTo("Redis 가 궁금해요");
        assertThat(((Number) row.get("participant_id")).longValue()).isEqualTo(joined.response().participantId());
        assertThat(row.get("host_user_id")).isNull();
        session.disconnect();
    }

    @Test
    void 강연자가_보낸_메시지는_그_강연자가_작성자로_저장된다() throws Exception {
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN, loginCookies());

        session.send(chatDestination(), Map.of("content", "질문 주세요"));

        Map<String, Object> row = awaitSavedMessage();
        assertThat(row.get("content")).isEqualTo("질문 주세요");
        assertThat(((Number) row.get("host_user_id")).longValue()).isEqualTo(host.getId());
        assertThat(row.get("participant_id")).isNull();
        session.disconnect();
    }

    @Test
    void 본문에_가짜_작성자를_적어도_무시하고_쿠키로_확인한_사람이_작성자다() throws Exception {
        var joined = participants.join(room.getJoinCode(), "청중A", UUID.randomUUID().toString(), Map.of());
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN,
                "PARTICIPANT_SESSION_" + room.getId() + "=" + joined.rawToken());

        // 청중이 강연자인 척하는 필드와 다른 참여자 번호를 함께 보낸다.
        session.send(chatDestination(), Map.of(
                "content", "가짜 작성자 시도",
                "hostUserId", host.getId(),
                "participantId", 999999,
                "sender", Map.of("type", "HOST", "id", host.getId())));

        Map<String, Object> row = awaitSavedMessage();
        assertThat(((Number) row.get("participant_id")).longValue()).isEqualTo(joined.response().participantId());
        assertThat(row.get("host_user_id")).isNull();
        session.disconnect();
    }

    @Test
    void 한_명이_보내면_같은_방_구독자_전원이_받는다() throws Exception {
        var a = join("청중A");
        var b = join("청중B");
        StompSession sessionA = client.connect(StompTestClient.ALLOWED_ORIGIN, a.cookie());
        StompSession sessionB = client.connect(StompTestClient.ALLOWED_ORIGIN, b.cookie());
        StompSession hostSession = client.connect(StompTestClient.ALLOWED_ORIGIN, loginCookies());
        var queueA = subscribeReady(sessionA, room);
        var queueB = subscribeReady(sessionB, room);
        var queueHost = subscribeReady(hostSession, room);

        sessionA.send(chatDestination(), Map.of("content", "안녕하세요"));

        // 보낸 사람을 포함해 같은 방 구독자 모두가 받는다.
        for (var queue : java.util.List.of(queueA, queueB, queueHost)) {
            Map<String, Object> event = StompTestClient.nextOfType(queue, "CHAT_CREATED", 5000);
            assertThat(event).isNotNull();
            assertThat(data(event).get("content")).isEqualTo("안녕하세요");
        }
        sessionA.disconnect();
        sessionB.disconnect();
        hostSession.disconnect();
    }

    @Test
    void 다른_방_구독자는_방송을_받지_못한다() throws Exception {
        LectureRoom otherRoom = liveRoom();
        var a = join("청중A");
        var other = join(otherRoom, "다른방청중");
        StompSession sessionA = client.connect(StompTestClient.ALLOWED_ORIGIN, a.cookie());
        StompSession sessionOther = client.connect(StompTestClient.ALLOWED_ORIGIN, other.cookie());
        var queueA = subscribeReady(sessionA, room);
        var queueOther = subscribeReady(sessionOther, otherRoom);

        sessionA.send(chatDestination(), Map.of("content", "우리 방 이야기"));

        // 우리 방에는 방송이 갔고(= 방송이 실제로 일어났고), 다른 방에는 오지 않는다.
        assertThat(StompTestClient.nextOfType(queueA, "CHAT_CREATED", 5000)).isNotNull();
        assertThat(StompTestClient.nextOfType(queueOther, "CHAT_CREATED", 1000)).isNull();
        sessionA.disconnect();
        sessionOther.disconnect();
    }

    @Test
    void 방송은_명세의_CHAT_CREATED_모양이다() throws Exception {
        var a = join("청중A");
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN, a.cookie());
        var queue = subscribeReady(session, room);

        session.send(chatDestination(), Map.of("content", "모양 확인"));

        Map<String, Object> data = data(StompTestClient.nextOfType(queue, "CHAT_CREATED", 5000));
        assertThat(((Number) data.get("messageId")).longValue()).isPositive();
        assertThat(((Number) data.get("roomId")).longValue()).isEqualTo(room.getId());
        assertThat(data.get("content")).isEqualTo("모양 확인");
        assertThat(data.get("sender")).isEqualTo(Map.of("type", "PARTICIPANT", "id", (int) a.participantId(), "name", "청중A"));
        // 시각은 숫자가 아니라 명세처럼 ISO-8601 UTC 문자열이어야 한다.
        assertThat(data.get("receivedAt")).isInstanceOf(String.class);
        assertThat(java.time.Instant.parse((String) data.get("receivedAt"))).isNotNull();
        session.disconnect();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> event) {
        return (Map<String, Object>) event.get("data");
    }

    /** 구독한 뒤, 서버가 그 구독을 등록해 방송이 닿는 것을 워밍업 방송으로 확인하고 큐를 돌려준다. */
    private java.util.concurrent.BlockingQueue<Map<String, Object>> subscribeReady(StompSession session,
            LectureRoom target) throws InterruptedException {
        String topic = "/topic/rooms/" + target.getId() + "/chat";
        var queue = client.subscribe(session, topic);
        assertThat(StompTestClient.awaitBroadcast(queue, () -> broker.convertAndSend(topic, Map.of("type", "WARMUP"))))
                .as("구독이 등록되어야 한다").isNotNull();
        return queue;
    }

    private LectureRoom liveRoom() {
        String code = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        LectureRoom created = rooms.saveAndFlush(new LectureRoom(host, "전송 테스트", "테스트 강연", code));
        jdbc.sql("UPDATE lecture_rooms SET status = 'LIVE' WHERE id = ?").param(created.getId()).update();
        return created;
    }

    private record Joined(String cookie, long participantId) { }

    private Joined join(String nickname) {
        return join(room, nickname);
    }

    private Joined join(LectureRoom target, String nickname) {
        var joined = participants.join(target.getJoinCode(), nickname, UUID.randomUUID().toString(), Map.of());
        return new Joined("PARTICIPANT_SESSION_" + target.getId() + "=" + joined.rawToken(),
                joined.response().participantId());
    }

    private String chatDestination() {
        return "/app/rooms/" + room.getId() + "/chat";
    }

    /** 저장은 서버에서 비동기로 일어나므로, 이 방의 첫 메시지가 저장될 때까지 기다렸다가 그 행을 돌려준다. */
    private Map<String, Object> awaitSavedMessage() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var rows = jdbc.sql("""
                            SELECT content, participant_id, host_user_id FROM chat_messages
                            WHERE lecture_room_id = ? ORDER BY id
                            """)
                    .param(room.getId()).query().listOfRows();
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("5초 안에 채팅 메시지가 저장되지 않았다");
    }

    /** CSRF 토큰을 받고 실제로 로그인해서, 브라우저가 갖게 되는 쿠키들을 그대로 돌려준다. */
    private String loginCookies() throws Exception {
        CookieManager cookies = new CookieManager();
        HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
        String base = "http://localhost:" + port;

        var csrf = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String token = json.readTree(csrf.body()).path("data").path("token").asText();

        var login = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/login"))
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", token)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                json.writeValueAsString(Map.of("email", hostEmail, "password", PASSWORD))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);

        return cookies.getCookieStore().getCookies().stream()
                .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                .collect(Collectors.joining("; "));
    }
}
