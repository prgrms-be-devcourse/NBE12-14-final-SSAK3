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
