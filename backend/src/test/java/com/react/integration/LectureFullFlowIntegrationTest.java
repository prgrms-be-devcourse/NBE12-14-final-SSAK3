package com.react.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.ai.dto.AudienceAnalysisLlmResponse;
import com.react.ai.repository.AiAnalysisFinalizationRepository;
import com.react.ai.repository.AiAnalysisRepository;
import com.react.ai.repository.AudienceMessageRepository;
import com.react.ai.service.AiAnalysisFinalizationService;
import com.react.ai.service.AiAnalysisJsonParser;
import com.react.ai.service.AiAnalysisWindowCalculator;
import com.react.ai.service.AudienceAnalysisService;
import com.react.auth.entity.User;
import com.react.auth.repository.UserRepository;
import com.react.report.service.ReportGenerationWorker;
import com.react.report.service.ReportOverviewClient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.session.cookie.secure=false",
                "app.ai.report.background-enabled=false"
        }
)
@ActiveProfiles("test")
class LectureFullFlowIntegrationTest {

    private static final String PASSWORD = "test-password-1!";
    private static final String TRANSCRIPT = "트랜잭션은 여러 작업을 하나의 단위로 처리합니다.";
    private static final String CHAT_CONTENT = "트랜잭션 예시가 궁금합니다.";

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
    private SimpMessagingTemplate broker;

    @Autowired
    private AiAnalysisFinalizationRepository finalizations;

    @Autowired
    private AiAnalysisRepository analyses;

    @Autowired
    private AudienceMessageRepository audienceMessages;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private ReportGenerationWorker reportWorker;

    @MockitoBean
    private ReportOverviewClient reportOverviewClient;

    @MockitoBean(name = "reportGenerationExecutor")
    private Executor reportExecutor;

    private Browser hostBrowser;
    private Browser audienceABrowser;
    private Browser audienceBBrowser;

    private long roomId;
    private String joinCode;

    @BeforeEach
    void setUp() throws Exception {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single())
                .endsWith("_test");

        Flyway.configure().dataSource(dataSource).load().migrate();

        hostBrowser = newBrowser();
        audienceABrowser = newBrowser();
        audienceBBrowser = newBrowser();

        String hostEmail = "lecture-" + UUID.randomUUID() + "@example.com";

        users.saveAndFlush(new User(
                hostEmail,
                passwordEncoder.encode(PASSWORD),
                "강연자"
        ));

        post(hostBrowser, "/api/v1/auth/login",
                Map.of("email", hostEmail, "password", PASSWORD), 200);

        JsonNode created = post(hostBrowser, "/api/v1/rooms",
                Map.of(
                        "title", "트랜잭션 강연",
                        "description", "강연 전체 흐름 통합 테스트"
                ), 201);

        roomId = created.path("roomId").asLong();
        joinCode = created.path("joinCode").asText();

        assertThat(roomId).isPositive();
        assertThat(joinCode).isNotBlank();
        assertThat(created.path("status").asText()).isEqualTo("READY");
    }

    @Test
    void 강연방_생성부터_청중_참여와_강연_종료_보고서_확인까지_정상적으로_진행된다() throws Exception {
        // 1. 청중 두 명이 강연방에 입장한다.
        JsonNode audienceA = join(audienceABrowser, "청중A");
        JsonNode audienceB = join(audienceBBrowser, "청중B");

        long participantAId = audienceA.path("participantId").asLong();
        long participantBId = audienceB.path("participantId").asLong();

        assertThat(participantAId).isPositive();
        assertThat(participantBId).isPositive();
        assertThat(participantAId).isNotEqualTo(participantBId);

        // 2. 강연자가 강연을 시작한다.
        JsonNode started = post(hostBrowser, roomPath() + "/start", Map.of(), 200);

        assertThat(started.path("status").asText()).isEqualTo("LIVE");
        assertThat(started.path("startedAt").asText()).isNotBlank();

        // 3. 강연자가 STT 녹음을 시작한다.
        String startKey = UUID.randomUUID().toString();

        JsonNode sttSession = post(hostBrowser, roomPath() + "/stt/sessions",
                Map.of("startKey", startKey), 201);

        long sttSessionId = sttSession.path("sttSessionId").asLong();
        String serverTime = sttSession.path("serverTime").asText();

        assertThat(sttSessionId).isPositive();
        assertThat(sttSession.path("status").asText()).isEqualTo("RECORDING");
        assertThat(serverTime).isNotBlank();

        // 4. 강연자의 발언을 저장한다.
        JsonNode segment = post(
                hostBrowser,
                roomPath() + "/stt/sessions/" + sttSessionId + "/segments",
                Map.of(
                        "startKey", startKey,
                        "segmentKey", UUID.randomUUID().toString(),
                        "transcript", TRANSCRIPT,
                        "segmentStartAt", serverTime,
                        "segmentEndAt", serverTime
                ),
                201
        );

        long segmentId = segment.path("segmentId").asLong();

        assertThat(segmentId).isPositive();
        assertThat(segment.path("transcript").asText()).isEqualTo(TRANSCRIPT);

        // 5. 청중이 실제 WebSocket으로 질문을 보내고 강연자가 수신한다.
        long chatMessageId = exchangeChat();

        assertThat(chatMessageId).isPositive();

        Long savedChatId = jdbc.sql("""
                        SELECT id
                        FROM chat_messages
                        WHERE lecture_room_id = ?
                          AND participant_id = ?
                          AND content = ?
                        """)
                .params(roomId, participantAId, CHAT_CONTENT)
                .query(Long.class)
                .single();

        assertThat(savedChatId).isEqualTo(chatMessageId);

        // 6. 강연자가 이해도 체크를 생성한다.
        JsonNode understandingPoll = post(
                hostBrowser,
                roomPath() + "/polls",
                Map.of(
                        "type", "UNDERSTANDING",
                        "question", "트랜잭션에 대해 이해하셨나요?"
                ),
                201
        );

        long understandingPollId = understandingPoll.path("pollId").asLong();

        JsonNode understandingOptions = understandingPoll.path("options");

        assertThat(understandingPollId).isPositive();
        assertThat(understandingOptions.size()).isGreaterThanOrEqualTo(2);

        long firstUnderstandingOption = understandingOptions.get(0)
                .path("optionId").asLong();

        long secondUnderstandingOption = understandingOptions.get(1)
                .path("optionId").asLong();

        // 7. 청중 두 명이 이해도 체크에 각각 응답한다.
        respond(audienceABrowser, understandingPollId, firstUnderstandingOption);
        respond(audienceBBrowser, understandingPollId, secondUnderstandingOption);

        JsonNode understandingClosed = post(
                hostBrowser,
                roomPath() + "/polls/" + understandingPollId + "/close",
                Map.of(),
                200
        );

        assertThat(understandingClosed.path("status").asText()).isEqualTo("CLOSED");

        JsonNode understandingResult = get(
                hostBrowser,
                roomPath() + "/polls/" + understandingPollId + "/results"
        );

        assertPollCounts(understandingResult, 2, 2, 0);

        // 8. 강연자가 일반 투표를 생성한다.
        JsonNode generalPoll = post(
                hostBrowser,
                roomPath() + "/polls",
                Map.of(
                        "type", "GENERAL",
                        "question", "어떤 예제가 더 필요한가요?",
                        "options", List.of("결제 예제", "예약 예제")
                ),
                201
        );

        long generalPollId = generalPoll.path("pollId").asLong();

        long generalOptionId = generalPoll.path("options").get(0)
                .path("optionId").asLong();

        assertThat(generalPollId).isPositive();
        assertThat(generalOptionId).isPositive();

        // 청중 A만 응답하고 청중 B는 응답하지 않는다.
        respond(audienceABrowser, generalPollId, generalOptionId);

        // 9. 일반적인 강연 종료 상황을 검증한다.
        // 마지막 발언과 강연 종료 시각이 서로 다른 초에 위치하도록 한다.
        // 초와 밀리초 사이의 시간 정밀도 문제는 이번 테스트 범위에서 제외한다.
        Thread.sleep(1100);

        // 일반 투표와 STT 녹음을 직접 종료하지 않은 상태에서 강연을 종료한다.
        JsonNode ended = post(
                hostBrowser,
                roomPath() + "/end",
                Map.of(),
                200
        );

        assertThat(ended.path("status").asText()).isEqualTo("ENDED");
        assertThat(ended.path("endedAt").asText()).isNotBlank();

        // 10. 강연 종료와 함께 일반 투표가 자동 마감되는지 확인한다.
        JsonNode generalResult = get(
                hostBrowser,
                roomPath() + "/polls/" + generalPollId + "/results"
        );

        assertThat(generalResult.path("status").asText()).isEqualTo("CLOSED");

        assertPollCounts(generalResult, 2, 1, 1);

        // 11. 강연 종료와 함께 STT 녹음이 자동 종료되는지 확인한다.
        String sttStatus = jdbc.sql("""
                        SELECT status
                        FROM stt_sessions
                        WHERE id = ?
                        """)
                .param(sttSessionId)
                .query(String.class)
                .single();

        assertThat(sttStatus).isEqualTo("STOPPED");

        LocalDateTime roomEndedAt = jdbc.sql("""
                        SELECT ended_at
                        FROM lecture_rooms
                        WHERE id = ?
                        """)
                .param(roomId)
                .query(LocalDateTime.class)
                .single();

        LocalDateTime sttEndedAt = jdbc.sql("""
                        SELECT ended_at
                        FROM stt_sessions
                        WHERE id = ?
                        """)
                .param(sttSessionId)
                .query(LocalDateTime.class)
                .single();

        assertThat(sttEndedAt).isEqualTo(roomEndedAt);

        // 12. 강연자가 보고서 생성을 요청한다.
        JsonNode receipt = post(
                hostBrowser,
                roomPath() + "/reports",
                Map.of(),
                202
        );

        long reportId = receipt.path("reportId").asLong();

        assertThat(reportId).isPositive();
        assertThat(receipt.path("status").asText()).isEqualTo("PENDING");

        // AI 분석이 확정되기 전에는 보고서가 생성되지 않아야 한다.
        reportWorker.check(roomId);

        JsonNode pendingReport = get(
                hostBrowser,
                roomPath() + "/reports/status"
        );

        assertThat(pendingReport.path("status").asText()).isEqualTo("PENDING");

        // 13. 외부 AI 서비스 대신 고정된 응답을 사용한다.
        when(reportOverviewClient.generate(any())).thenReturn(
                json.readTree("""
                        {
                          "overview": {
                            "mainInterests": [],
                            "reviewPoints": [],
                            "suggestions": []
                          },
                          "followUp": {
                            "reviewedChatIds": [%d],
                            "groups": []
                          }
                        }
                        """.formatted(chatMessageId))
        );

        // 실제 AI 분석 종료 처리 로직을 실행한다.
        finalizeAnalysis();

        // 테스트에서는 보고서 작업 실행 시점을 직접 제어한다.
        reportWorker.check(roomId);

        // 14. 강연자가 생성된 보고서를 조회한다.
        JsonNode report = get(
                hostBrowser,
                roomPath() + "/reports"
        );

        assertThat(report.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(report.path("reportId").asLong()).isEqualTo(reportId);
        assertThat(report.path("roomId").asLong()).isEqualTo(roomId);

        JsonNode timeline = report.path("content").path("timeline");

        assertThat(timeline.isArray()).isTrue();
        assertThat(timeline.size()).isPositive();

        // 15. 보고서에 실제 강연 발언, 채팅, 투표 결과가 보존되는지 확인한다.
        List<JsonNode> speechRecords = collect(timeline, "sttSegments");
        List<JsonNode> chatRecords = collect(timeline, "chatMessages");
        List<JsonNode> understandingRecords = collect(timeline, "understandingChecks");
        List<JsonNode> pollRecords = collect(timeline, "polls");

        assertThat(speechRecords)
                .anySatisfy(record -> {
                    assertThat(record.path("segmentId").asLong()).isEqualTo(segmentId);
                    assertThat(record.path("transcript").asText()).isEqualTo(TRANSCRIPT);
                });

        assertThat(chatRecords)
                .anySatisfy(record -> {
                    assertThat(record.path("messageId").asLong()).isEqualTo(chatMessageId);
                    assertThat(record.path("text").asText()).isEqualTo(CHAT_CONTENT);
                });

        assertThat(understandingRecords)
                .anySatisfy(record -> {
                    assertThat(record.path("pollId").asLong()).isEqualTo(understandingPollId);
                    assertPollCounts(record, 2, 2, 0);
                });

        assertThat(pollRecords)
                .anySatisfy(record -> {
                    assertThat(record.path("pollId").asLong()).isEqualTo(generalPollId);
                    assertThat(record.path("status").asText()).isEqualTo("CLOSED");
                    assertPollCounts(record, 2, 1, 1);
                });

        // 16. 보고서를 다시 요청해도 동일한 보고서를 반환한다.
        JsonNode requestedAgain = post(
                hostBrowser,
                roomPath() + "/reports",
                Map.of(),
                200
        );

        assertThat(requestedAgain.path("reportId").asLong()).isEqualTo(reportId);
        assertThat(requestedAgain.path("status").asText()).isEqualTo("COMPLETED");

        JsonNode retried = post(
                hostBrowser,
                roomPath() + "/reports/retry",
                Map.of(),
                200
        );

        assertThat(retried.path("reportId").asLong()).isEqualTo(reportId);
        assertThat(retried.path("status").asText()).isEqualTo("COMPLETED");

        Long reportCount = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM lecture_reports
                        WHERE lecture_room_id = ?
                        """)
                .param(roomId)
                .query(Long.class)
                .single();

        assertThat(reportCount).isEqualTo(1L);
    }

    private void finalizeAnalysis() {
        AiAnalysisFinalizationRepository.EndedRoom room = jdbc.sql("""
                        SELECT id, started_at, ended_at
                        FROM lecture_rooms
                        WHERE id = ?
                        """)
                .param(roomId)
                .query((rs, rowNum) -> new AiAnalysisFinalizationRepository.EndedRoom(
                        rs.getLong("id"),
                        rs.getObject("started_at", LocalDateTime.class),
                        rs.getObject("ended_at", LocalDateTime.class)
                ))
                .single();

        AudienceAnalysisService audienceService = new AudienceAnalysisService(
                analyses,
                audienceMessages,
                messages -> new AudienceAnalysisLlmResponse(
                        "{\"topics\":[]}",
                        "test"
                ),
                new AiAnalysisJsonParser(json)
        );

        AiAnalysisFinalizationService finalizer = new AiAnalysisFinalizationService(
                finalizations,
                analyses,
                audienceService,
                new AiAnalysisWindowCalculator(),
                events
        );

        ReflectionTestUtils.invokeMethod(
                finalizer,
                "processRoom",
                room,
                100,
                Duration.ofSeconds(90)
        );

        String status = jdbc.sql("""
                        SELECT status
                        FROM ai_analysis_finalizations
                        WHERE lecture_room_id = ?
                        """)
                .param(roomId)
                .query(String.class)
                .single();

        assertThat(status).isEqualTo("COMPLETED");
    }

    private long exchangeChat() throws Exception {
        WebSocketStompClient client =
                new WebSocketStompClient(new StandardWebSocketClient());

        client.setMessageConverter(new MappingJackson2MessageConverter());

        StompSession host = null;
        StompSession audience = null;

        try {
            host = connect(client, hostBrowser, false);
            audience = connect(client, audienceABrowser, true);

            BlockingQueue<JsonNode> hostMessages = subscribe(host);
            BlockingQueue<JsonNode> audienceMessages = subscribe(audience);

            String attempt = UUID.randomUUID().toString();

            StompHeaders headers = new StompHeaders();
            headers.setDestination("/app/rooms/" + roomId + "/chat");
            headers.set("X-Chat-Attempt", attempt);

            audience.send(headers, Map.of("content", CHAT_CONTENT));

            long hostMessageId = receiveChat(hostMessages, attempt);
            long audienceMessageId = receiveChat(audienceMessages, attempt);

            assertThat(hostMessageId).isEqualTo(audienceMessageId);

            return hostMessageId;
        } finally {
            disconnect(audience);
            disconnect(host);
            client.stop();
        }
    }

    private StompSession connect(
            WebSocketStompClient client,
            Browser browser,
            boolean participant
    ) throws Exception {
        String token = csrf(browser);

        String sessionCookie = browser.cookies().getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("HOST_SESSION"))
                .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                .findFirst()
                .orElseThrow();

        String cookies = participant
                ? sessionCookie + "; " + browser.participantCookie
                : sessionCookie;

        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.setOrigin("http://localhost:5173");
        handshakeHeaders.add("Cookie", cookies);

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("X-CSRF-TOKEN", token);

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
        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();

        String topic = "/topic/rooms/" + roomId + "/chat";

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

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (System.nanoTime() < deadline) {
            broker.convertAndSend(topic, Map.of("type", "WARMUP"));

            JsonNode event = received.poll(100, TimeUnit.MILLISECONDS);

            if (event != null && event.path("type").asText().equals("WARMUP")) {
                return received;
            }
        }

        throw new AssertionError("WebSocket 구독에 실패했다");
    }

    private long receiveChat(
            BlockingQueue<JsonNode> received,
            String attempt
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (System.nanoTime() < deadline) {
            JsonNode event = received.poll(100, TimeUnit.MILLISECONDS);

            if (event == null || !event.path("type").asText().equals("CHAT_BATCH")) {
                continue;
            }

            for (JsonNode message : event.path("data").path("messages")) {
                if (!message.path("attempt").asText().equals(attempt)) {
                    continue;
                }

                assertThat(message.path("content").asText()).isEqualTo(CHAT_CONTENT);
                assertThat(message.path("roomId").asLong()).isEqualTo(roomId);
                assertThat(message.path("messageId").asLong()).isPositive();

                return message.path("messageId").asLong();
            }
        }

        throw new AssertionError("실시간 채팅을 수신하지 못했다");
    }

    private JsonNode join(Browser browser, String nickname) throws Exception {
        HttpResponse<String> response = request(
                browser,
                "POST",
                "/api/v1/public/rooms/" + joinCode + "/participants",
                Map.of("nickname", nickname),
                201
        );

        browser.participantCookie = response.headers().allValues("Set-Cookie").stream()
                .map(value -> value.split(";", 2)[0])
                .filter(value -> value.startsWith("PARTICIPANT_SESSION_" + roomId + "="))
                .findFirst()
                .orElseThrow();

        return json.readTree(response.body()).path("data");
    }

    private void respond(
            Browser browser,
            long pollId,
            long optionId
    ) throws Exception {
        request(
                browser,
                "PUT",
                roomPath() + "/polls/" + pollId + "/responses/me",
                Map.of("optionId", optionId),
                200
        );
    }

    private void assertPollCounts(
            JsonNode result,
            long total,
            long responded,
            long unresponded
    ) {
        assertThat(result.path("totalParticipants").asLong()).isEqualTo(total);
        assertThat(result.path("respondedParticipants").asLong()).isEqualTo(responded);
        assertThat(result.path("unrespondedParticipants").asLong()).isEqualTo(unresponded);
    }

    private List<JsonNode> collect(JsonNode timeline, String field) {
        List<JsonNode> records = new ArrayList<>();

        for (JsonNode window : timeline) {
            for (JsonNode record : window.path(field)) {
                records.add(record);
            }
        }

        return records;
    }

    private Browser newBrowser() {
        CookieManager cookies = new CookieManager();

        HttpClient http = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        return new Browser(http, cookies);
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

    private JsonNode post(
            Browser browser,
            String path,
            Map<String, Object> body,
            int expectedStatus
    ) throws Exception {
        HttpResponse<String> response = request(
                browser,
                "POST",
                path,
                body,
                expectedStatus
        );

        return json.readTree(response.body()).path("data");
    }

    private JsonNode get(Browser browser, String path) throws Exception {
        HttpResponse<String> response = browser.http().send(
                HttpRequest.newBuilder(URI.create(baseUrl() + path))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertThat(response.statusCode())
                .as(path + ": " + response.body())
                .isEqualTo(200);

        return json.readTree(response.body()).path("data");
    }

    private HttpResponse<String> request(
            Browser browser,
            String method,
            String path,
            Map<String, Object> body,
            int expectedStatus
    ) throws Exception {
        String token = csrf(browser);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", token);

        if (path.endsWith("/participants")) {
            builder.header("Idempotency-Key", UUID.randomUUID().toString());
        }

        if (browser.participantCookie != null) {
            boolean hasCookie = browser.cookies().getCookieStore().getCookies().stream()
                    .anyMatch(cookie -> browser.participantCookie.startsWith(cookie.getName() + "="));

            if (!hasCookie || browser.cookies().getCookieStore().getCookies().stream()
                    .anyMatch(cookie -> cookie.getSecure()
                            && browser.participantCookie.startsWith(cookie.getName() + "="))) {
                builder.header("Cookie", browser.participantCookie);
            }
        }

        HttpResponse<String> response = browser.http().send(
                builder.method(
                        method,
                        HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))
                ).build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertThat(response.statusCode())
                .as(method + " " + path + ": " + response.body())
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

    private static final class Browser {
        private final HttpClient http;
        private final CookieManager cookies;
        private String participantCookie;

        private Browser(HttpClient http, CookieManager cookies) {
            this.http = http;
            this.cookies = cookies;
        }

        private HttpClient http() {
            return http;
        }

        private CookieManager cookies() {
            return cookies;
        }
    }
}