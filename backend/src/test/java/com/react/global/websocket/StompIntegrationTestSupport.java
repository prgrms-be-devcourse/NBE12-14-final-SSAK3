package com.react.global.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.auth.entity.User;
import com.react.auth.repository.UserRepository;
import com.react.room.entity.LectureRoom;
import com.react.room.repository.LectureRoomRepository;
import com.react.room.service.LectureParticipantService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.simp.SimpMessagingTemplate;
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
 * STOMP 통합 테스트의 공통 준비물: 실제 서버를 띄우고, 테스트 DB 인지 확인하고, 강연자·강연방·쿠키를 만드는 도우미.
 * 같은 설정을 쓰는 하위 테스트끼리 스프링 컨텍스트도 함께 재사용된다.
 */
// 세션 쿠키는 기본이 Secure(HTTPS 전용)라 일반 HTTP 테스트 요청에는 실리지 않는다. 이 테스트들에서만 끈다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.servlet.session.cookie.secure=false")
@ActiveProfiles("test")
abstract class StompIntegrationTestSupport {
    protected static final String PASSWORD = "test-password-1!";

    @LocalServerPort protected int port;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected DataSource dataSource;
    @Autowired protected UserRepository users;
    @Autowired protected LectureRoomRepository rooms;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected LectureParticipantService participants;
    @Autowired protected SimpMessagingTemplate broker;

    protected StompTestClient client;

    @BeforeEach
    void prepareStomp() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        client = new StompTestClient(port);
    }

    protected User newHost(String email) {
        return users.saveAndFlush(new User(email, passwordEncoder.encode(PASSWORD), "강연자"));
    }

    protected LectureRoom newRoom(User owner, String title) {
        String code = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        return rooms.saveAndFlush(new LectureRoom(owner, title, "테스트 강연", code));
    }

    /** CSRF 토큰을 받고 실제로 로그인해서, 브라우저가 갖게 되는 쿠키들을 그대로 돌려준다. */
    protected String loginCookies(String email) throws Exception {
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
