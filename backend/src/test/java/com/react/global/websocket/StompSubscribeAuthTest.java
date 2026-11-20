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
import org.springframework.messaging.simp.SimpMessagingTemplate;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 구독 권한: 그 방의 강연자와 청중만 방별 채널을 구독할 수 있다.
 * 강연자는 실제로 로그인해서 받은 세션 쿠키로 연결해, 로그인 정보가 STOMP 에서도 인식되는지 함께 확인한다.
 */
// 세션 쿠키는 기본이 Secure(HTTPS 전용)라 일반 HTTP 테스트 요청에는 실리지 않는다. 이 테스트에서만 끈다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.servlet.session.cookie.secure=false")
@ActiveProfiles("test")
class StompSubscribeAuthTest {
    private static final String PASSWORD = "test-password-1!";

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired UserRepository users;
    @Autowired LectureRoomRepository rooms;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired LectureParticipantService participants;
    @Autowired SimpMessagingTemplate broker;

    private StompTestClient client;
    private User host;
    private String hostEmail;
    private LectureRoom roomA;
    private LectureRoom roomB;

    @BeforeEach
    void prepare() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        client = new StompTestClient(port);
        hostEmail = "host-" + UUID.randomUUID() + "@example.com";
        host = newHost(hostEmail);
        roomA = newRoom(host);
        roomB = newRoom(host);
    }

    @Test
    void 그_방_청중은_구독하고_방송을_받는다() throws Exception {
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN, participantCookie(roomA));
        var received = client.subscribe(session, chatTopic(roomA));

        assertThat(StompTestClient.awaitBroadcast(received, () -> broadcast(roomA, "안녕하세요")))
                .containsEntry("type", "CHAT_CREATED");
        session.disconnect();
    }

    @Test
    void 방_주인_강연자는_로그인_세션으로_구독하고_방송을_받는다() throws Exception {
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN, loginCookies(hostEmail));
        var received = client.subscribe(session, chatTopic(roomA));

        assertThat(StompTestClient.awaitBroadcast(received, () -> broadcast(roomA, "강연자 화면")))
                .containsEntry("type", "CHAT_CREATED");
        session.disconnect();
    }

    @Test
    void 다른_방_구독은_거절되고_연결이_끊긴다() throws Exception {
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN, participantCookie(roomA));
        client.subscribe(session, chatTopic(roomB));

        assertThat(StompTestClient.awaitClosed(session)).isTrue();
    }

    @Test
    void 쿠키도_로그인도_없으면_구독이_거절된다() throws Exception {
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN);
        client.subscribe(session, chatTopic(roomA));

        assertThat(StompTestClient.awaitClosed(session)).isTrue();
    }

    @Test
    void 다른_강연자는_내_방을_구독할_수_없다() throws Exception {
        String otherEmail = "other-" + UUID.randomUUID() + "@example.com";
        newHost(otherEmail);
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN, loginCookies(otherEmail));
        client.subscribe(session, chatTopic(roomA));

        assertThat(StompTestClient.awaitClosed(session)).isTrue();
    }

    @Test
    void 허용_목록에_없는_주소_구독은_거절된다() throws Exception {
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN, participantCookie(roomA));
        client.subscribe(session, "/topic/rooms/" + roomA.getId() + "/secret");

        assertThat(StompTestClient.awaitClosed(session)).isTrue();
    }

    @Test
    void 강연_상태_알림도_그_방_사람만_구독할_수_있다() throws Exception {
        StompSession ownSession = client.connect(StompTestClient.ALLOWED_ORIGIN, participantCookie(roomA));
        var received = client.subscribe(ownSession, "/topic/rooms/" + roomA.getId() + "/status");

        assertThat(StompTestClient.awaitBroadcast(received, () -> broker.convertAndSend(
                "/topic/rooms/" + roomA.getId() + "/status", Map.of("type", "ROOM_STATUS_CHANGED"))))
                .containsEntry("type", "ROOM_STATUS_CHANGED");
        ownSession.disconnect();

        StompSession otherSession = client.connect(StompTestClient.ALLOWED_ORIGIN, participantCookie(roomA));
        client.subscribe(otherSession, "/topic/rooms/" + roomB.getId() + "/status");

        assertThat(StompTestClient.awaitClosed(otherSession)).isTrue();
    }

    private void broadcast(LectureRoom room, String content) {
        broker.convertAndSend("/topic/rooms/" + room.getId() + "/chat",
                Map.of("type", "CHAT_CREATED", "data", Map.of("content", content)));
    }

    private static String chatTopic(LectureRoom room) {
        return "/topic/rooms/" + room.getId() + "/chat";
    }

    /** 청중 입장 서비스로 실제 토큰을 발급받아, 브라우저가 보내는 것과 같은 모양의 쿠키 문자열을 만든다. */
    private String participantCookie(LectureRoom room) {
        var joined = participants.join(room.getJoinCode(), "청중", UUID.randomUUID().toString(), Map.of());
        return "PARTICIPANT_SESSION_" + room.getId() + "=" + joined.rawToken();
    }

    private User newHost(String email) {
        return users.saveAndFlush(new User(email, passwordEncoder.encode(PASSWORD), "강연자"));
    }

    private LectureRoom newRoom(User owner) {
        String code = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        return rooms.saveAndFlush(new LectureRoom(owner, "구독 테스트", "테스트 강연", code));
    }

    /** CSRF 토큰을 받고 실제로 로그인해서, 브라우저가 갖게 되는 쿠키들을 그대로 돌려준다. */
    private String loginCookies(String email) throws Exception {
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
                                json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);

        return cookies.getCookieStore().getCookies().stream()
                .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                .collect(Collectors.joining("; "));
    }
}
