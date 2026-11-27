package com.react.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.react.ai.config.AudienceAnalysisCostProperties;
import com.react.ai.config.AudienceAnalysisRetryProperties;
import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.dto.AiAnalysisProcessResult;
import com.react.ai.dto.AudienceAnalysisLlmResponse;
import com.react.ai.exception.GeminiAudienceAnalysisErrorCode;
import com.react.ai.exception.GeminiAudienceAnalysisException;
import com.react.ai.repository.AiAnalysisFinalizationRepository;
import com.react.ai.repository.AiAnalysisRepository;
import com.react.ai.repository.AudienceMessageRepository;
import com.react.ai.service.AiAnalysisCompletionService;
import com.react.ai.service.AiAnalysisFinalizationService;
import com.react.ai.service.AiAnalysisJsonParser;
import com.react.ai.service.AiAnalysisWindowCalculator;
import com.react.ai.service.AudienceAnalysisLlmClient;
import com.react.ai.service.AudienceAnalysisService;
import com.react.report.service.ReportGenerationWorker;
import com.react.report.service.ReportOverviewClient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
class LectureFailureIsolationIntegrationTest {

    private static final String PASSWORD = "test-password-1!";
    private static final ZoneOffset STORAGE_OFFSET = ZoneOffset.ofHours(9);

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SimpMessagingTemplate broker;

    @Autowired
    private AiAnalysisRepository analyses;

    @Autowired
    private AiAnalysisFinalizationRepository finalizations;

    @Autowired
    private AudienceMessageRepository audienceMessages;

    @Autowired
    private AiAnalysisCompletionService completions;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private ReportGenerationWorker reportWorker;

    @MockitoBean
    private ReportOverviewClient reportOverviewClient;

    @MockitoBean(name = "reportGenerationExecutor")
    private Executor reportExecutor;

    private Browser hostBrowser;
    private Browser audienceBrowser;

    private WebSocketStompClient stompClient;
    private StompSession hostSession;
    private StompSession audienceSession;

    private BlockingQueue<JsonNode> hostMessages;
    private BlockingQueue<JsonNode> audienceChatMessages;

    private long roomId;
    private long participantId;
    private String joinCode;

    @BeforeEach
    void setUp() throws Exception {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single())
                .endsWith("_test");

        Flyway.configure().dataSource(dataSource).load().migrate();

        hostBrowser = newBrowser();
        audienceBrowser = newBrowser();

        String email = "isolation-" + UUID.randomUUID() + "@example.com";

        post(hostBrowser, "/api/v1/auth/signup", Map.of(
                "email", email,
                "password", PASSWORD,
                "name", "강연자"
        ), 201);

        post(hostBrowser, "/api/v1/auth/login", Map.of(
                "email", email,
                "password", PASSWORD
        ), 200);

        JsonNode room = post(hostBrowser, "/api/v1/rooms", Map.of(
                "title", "강연 장애 격리 통합 테스트",
                "description", "AI와 STT 장애 중 다른 기능 검증"
        ), 201);

        roomId = room.path("roomId").asLong();
        joinCode = room.path("joinCode").asText();

        JsonNode participant = post(
                audienceBrowser,
                "/api/v1/public/rooms/" + joinCode + "/participants",
                Map.of("nickname", "청중"),
                201
        );

        participantId = participant.path("participantId").asLong();

        assertThat(roomId).isPositive();
        assertThat(participantId).isPositive();

        JsonNode started = post(hostBrowser, roomPath() + "/start", Map.of(), 200);

        assertThat(started.path("status").asText()).isEqualTo("LIVE");

        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        hostSession = connect(hostBrowser, "HOST");
        audienceSession = connect(audienceBrowser, "PARTICIPANT");

        hostMessages = subscribeReady(hostSession, chatTopic());
        audienceChatMessages = subscribeReady(audienceSession, chatTopic());
    }

    @AfterEach
    void tearDown() {
        disconnect(audienceSession);
        disconnect(hostSession);

        if (stompClient != null) {
            stompClient.stop();
        }
    }

    @Test
    void AI_분석에_실패해도_채팅과_투표와_발언_기록은_유지된다() throws Exception {
        // 청중이 질문을 보내고 강연자와 청중이 같은 메시지를 받는다.
        long firstChatId = exchangeChat("트랜잭션은 왜 필요한가요?");

        LocalDateTime startedAt = jdbc.sql("""
                        SELECT started_at
                        FROM lecture_rooms
                        WHERE id = ?
                        """)
                .param(roomId)
                .query(LocalDateTime.class)
                .single();

        LocalDateTime windowEnd = startedAt.plusSeconds(30);

        // 실제 첫 번째 AI 정규 분석 구간이 끝날 때까지 기다린다.
        waitUntil(windowEnd.toInstant(STORAGE_OFFSET));

        CountDownLatch analysisStarted = new CountDownLatch(1);
        CountDownLatch allowFailure = new CountDownLatch(1);

        AudienceAnalysisService failingAnalysis = analysisService(messages -> {
            assertThat(messages)
                    .extracting(message -> message.messageId())
                    .contains(firstChatId);

            analysisStarted.countDown();

            try {
                assertThat(allowFailure.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }

            throw new GeminiAudienceAnalysisException(
                    GeminiAudienceAnalysisErrorCode.HTTP_5XX,
                    true,
                    null,
                    "테스트용 Gemini 서비스 장애"
            );
        });

        long secondChatId;
        long pollId;
        long segmentId;
        long failedAnalysisId;

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<AiAnalysisProcessResult> analysis = executor.submit(
                    () -> failingAnalysis.process(roomId, startedAt, windowEnd)
            );

            try {
                assertThat(analysisStarted.await(10, TimeUnit.SECONDS)).isTrue();

                // AI 분석이 완료되지 않은 상태에서도 실시간 채팅이 가능해야 한다.
                secondChatId = exchangeChat("트랜잭션 예시도 보여주세요.");

                // 강연자는 발언을 계속 기록할 수 있어야 한다.
                Recording recording = startRecording();

                segmentId = saveSegment(
                        recording,
                        "트랜잭션은 여러 작업의 실행 결과를 하나로 관리합니다.",
                        Instant.parse(recording.serverTime())
                );

                // AI 처리 중에도 이해도 체크와 응답이 가능해야 한다.
                JsonNode poll = createUnderstandingPoll();
                pollId = poll.path("pollId").asLong();

                respond(pollId, poll.path("options").get(0).path("optionId").asLong(), 200);

                closePoll(pollId);

                assertPollCounts(pollId, 1, 1, 0);

            } finally {
                allowFailure.countDown();
            }

            AiAnalysisProcessResult result = analysis.get(10, TimeUnit.SECONDS);

            assertThat(result.status()).isEqualTo(AiAnalysisStatus.FAILED);

            failedAnalysisId = result.analysisId();
        }

        // AI가 실제로 실패한 이후에도 채팅이 계속 전달되어야 한다.
        long thirdChatId = exchangeChat("설명 감사합니다.");

        JsonNode liveRoom = get(hostBrowser, roomPath());

        assertThat(liveRoom.path("status").asText()).isEqualTo("LIVE");

        JsonNode aiResults = get(hostBrowser, roomPath() + "/ai/analyses");

        assertThat(elements(aiResults.path("analyses")))
                .anySatisfy(result -> {
                    assertThat(result.path("analysisId").asLong())
                            .isEqualTo(failedAnalysisId);

                    assertThat(result.path("status").asText())
                            .isEqualTo("FAILED");
                });

        // 종료 시각과 마지막 STT 및 채팅 시각을 구분한다.
        Thread.sleep(1100);

        JsonNode report = endAndGenerateReport(
                emptyAnalysis(),
                List.of(firstChatId, secondChatId, thirdChatId)
        );

        JsonNode content = report.path("content");

        assertThat(records(content, "chatMessages"))
                .extracting(record -> record.path("messageId").asLong())
                .contains(firstChatId, secondChatId, thirdChatId);

        assertThat(records(content, "sttSegments"))
                .extracting(record -> record.path("segmentId").asLong())
                .contains(segmentId);

        assertThat(records(content, "understandingChecks"))
                .anySatisfy(record -> {
                    assertThat(record.path("pollId").asLong()).isEqualTo(pollId);
                    assertThat(record.path("respondedParticipants").asLong()).isEqualTo(1L);
                    assertThat(record.path("unrespondedParticipants").asLong()).isZero();
                });

        assertThat(records(content, "audienceOpinions"))
                .anySatisfy(record -> {
                    assertThat(record.path("analysisId").asLong())
                            .isEqualTo(failedAnalysisId);

                    assertThat(record.path("status").asText())
                            .isEqualTo("FAILED");
                });

        assertThat(content.path("dataLimitations").toString())
                .contains("청중 의견 AI 분석 실패");

        assertThat(chatCount()).isEqualTo(3L);
    }

    @Test
    void STT_인식이_중단되어도_강연은_계속되고_복구된_기록과_AI_분석이_보고서에_연결된다() throws Exception {
        Recording recording = startRecording();

        long firstSegmentId = saveSegment(
                recording,
                "지금부터 데이터베이스 트랜잭션을 설명하겠습니다.",
                Instant.parse(recording.serverTime())
        );

        // 강연자의 음성 인식 오류를 서버에 기록한다.
        Instant gapStartedAt = Instant.now();

        JsonNode gap = post(
                hostBrowser,
                roomPath() + "/stt/sessions/" + recording.sessionId() + "/gaps",
                Map.of(
                        "startKey", recording.startKey(),
                        "eventKey", UUID.randomUUID().toString(),
                        "reason", "ERROR",
                        "startedAt", gapStartedAt.toString()
                ),
                201
        );

        long gapId = gap.path("gapId").asLong();

        assertThat(gapId).isPositive();
        assertThat(gap.path("reason").asText()).isEqualTo("ERROR");
        assertThat(gap.path("endedAt").isNull()).isTrue();

        // STT 중단 중에도 청중이 질문을 보내고 강연자가 수신한다.
        String question = "트랜잭션이 실패하면 어떻게 되나요?";
        long messageId = exchangeChat(question);

        // STT 중단 중에도 일반 투표를 사용할 수 있어야 한다.
        JsonNode poll = post(
                hostBrowser,
                roomPath() + "/polls",
                Map.of(
                        "type", "GENERAL",
                        "question", "추가로 어떤 설명이 필요한가요?",
                        "options", List.of("롤백", "커밋")
                ),
                201
        );

        long pollId = poll.path("pollId").asLong();
        long selectedOption = poll.path("options").get(0).path("optionId").asLong();
        long otherOption = poll.path("options").get(1).path("optionId").asLong();

        respond(pollId, selectedOption, 200);

        assertPollCounts(pollId, 1, 1, 0);

        // 녹음 누락 시작과 복구 시각을 구분한다.
        Thread.sleep(1100);

        Instant recoveredAt = Instant.now();

        JsonNode recovered = request(
                hostBrowser,
                "PATCH",
                roomPath() + "/stt/sessions/" + recording.sessionId()
                        + "/gaps/" + gapId + "/close",
                Map.of(
                        "startKey", recording.startKey(),
                        "endedAt", recoveredAt.toString()
                ),
                200
        ).path("data");

        assertThat(recovered.path("gapId").asLong()).isEqualTo(gapId);
        assertThat(recovered.path("endedAt").asText()).isNotBlank();

        // 음성 인식 복구 이후 새로운 발언을 정상적으로 저장한다.
        String transcript = "트랜잭션이 실패하면 롤백을 통해 이전 상태로 되돌립니다.";

        long recoveredSegmentId = saveSegment(recording, transcript, recoveredAt);

        JsonNode sttRecords = get(hostBrowser, roomPath() + "/stt/segments");

        assertThat(elements(sttRecords.path("segments")))
                .extracting(record -> record.path("segmentId").asLong())
                .contains(firstSegmentId, recoveredSegmentId);

        assertThat(elements(sttRecords.path("gaps")))
                .anySatisfy(record -> {
                    assertThat(record.path("gapId").asLong()).isEqualTo(gapId);
                    assertThat(record.path("reason").asText()).isEqualTo("ERROR");
                    assertThat(record.path("endedAt").asText()).isNotBlank();
                });

        // 실제 청중 채팅을 참조하는 AI 분석 결과를 생성한다.
        AudienceAnalysisService successfulAnalysis = analysisService(messages -> {
            ObjectNode response = json.createObjectNode();
            ObjectNode topic = response.putArray("topics").addObject();

            topic.put("topic", "트랜잭션 실패 처리");
            topic.put("summary", "트랜잭션 실패 시 처리 방법에 대한 질문입니다.");
            topic.put("category", "QUESTION");

            messages.forEach(message ->
                    topic.withArray("sourceMessageIds").add(message.messageId()));

            return new AudienceAnalysisLlmResponse(response.toString(), "test-model");
        });

        // 실시간 AI 분석 완료 이벤트를 구독한다.
        BlockingQueue<JsonNode> aiEvents = subscribeReady(
                hostSession,
                "/topic/rooms/" + roomId + "/ai"
        );

        Thread.sleep(1100);

        // 진행 중인 일반 투표는 강연 종료와 함께 자동 마감되어야 한다.
        JsonNode ended = post(hostBrowser, roomPath() + "/end", Map.of(), 200);

        assertThat(ended.path("status").asText()).isEqualTo("ENDED");

        assertPollCounts(pollId, 1, 1, 0);

        // 강연 종료 이후 기존 청중이 투표 선택을 바꾸면 거절되어야 한다.
        JsonNode rejectedVote = respond(pollId, otherOption, 409);

        assertThat(rejectedVote.path("code").asText())
                .isIn("ROOM_ENDED", "ROOM_NOT_LIVE", "POLL_CLOSED");

        // 종료된 강연방에 새로운 청중이 입장하면 거절되어야 한다.
        Browser lateAudience = newBrowser();

        JsonNode rejectedJoin = request(
                lateAudience,
                "POST",
                "/api/v1/public/rooms/" + joinCode + "/participants",
                Map.of("nickname", "늦게 온 청중"),
                409
        );

        assertThat(rejectedJoin.path("code").asText()).isEqualTo("ROOM_ENDED");

        // 종료 후 채팅을 전송해도 새로운 DB 기록이 생기면 안 된다.
        assertEndedChatRejected();

        assertThat(chatCount()).isEqualTo(1L);

        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM lecture_participants
                        WHERE lecture_room_id = ?
                        """)
                .param(roomId)
                .query(Long.class)
                .single()).isEqualTo(1L);

        // 기존 채팅 내역은 종료 이후에도 조회할 수 있어야 한다.
        JsonNode history = get(audienceBrowser, roomPath() + "/messages");

        assertThat(elements(history.path("messages")))
                .extracting(record -> record.path("messageId").asLong())
                .contains(messageId);

        assertPollCounts(pollId, 1, 1, 0);

        JsonNode report = generateReport(successfulAnalysis, List.of(messageId));

        // 실제 저장된 AI 분석 결과가 강연자에게 전달되는지 확인한다.
        JsonNode aiEvent = awaitEvent(
                aiEvents,
                "AI_ANALYSIS_COMPLETED",
                null
        );

        assertThat(aiEvent.path("data").path("roomId").asLong())
                .isEqualTo(roomId);

        // 강연자는 AI 주제 그룹에서 실제 질문 원문을 확인할 수 있어야 한다.
        JsonNode aiResults = get(hostBrowser, roomPath() + "/ai/analyses");

        long topicGroupId = findTopicGroup(aiResults, messageId);

        assertThat(topicGroupId).isPositive();

        JsonNode sources = get(
                hostBrowser,
                roomPath() + "/ai/topic-groups/" + topicGroupId + "/messages"
        );

        assertThat(elements(sources.path("messages")))
                .anySatisfy(source -> {
                    assertThat(source.path("messageId").asLong()).isEqualTo(messageId);
                    assertThat(source.path("participantId").asLong()).isEqualTo(participantId);
                    assertThat(source.path("content").asText()).isEqualTo(question);
                });

        JsonNode content = report.path("content");

        // 보고서에는 오류 이전과 복구 이후 발언이 모두 남아야 한다.
        assertThat(records(content, "sttSegments"))
                .extracting(record -> record.path("segmentId").asLong())
                .contains(firstSegmentId, recoveredSegmentId);

        // 누락 구간도 별도로 표시되어야 한다.
        assertThat(records(content, "sttGaps"))
                .anySatisfy(record -> {
                    assertThat(record.path("gapId").asLong()).isEqualTo(gapId);
                    assertThat(record.path("reason").asText()).isEqualTo("ERROR");
                });

        // 원본 채팅이 유지되어야 한다.
        assertThat(records(content, "chatMessages"))
                .anySatisfy(record -> {
                    assertThat(record.path("messageId").asLong()).isEqualTo(messageId);
                    assertThat(record.path("text").asText()).isEqualTo(question);
                });

        // AI 분석 결과와 실제 채팅 원문이 보고서에서도 연결되어야 한다.
        assertThat(records(content, "audienceOpinions"))
                .anySatisfy(record -> {
                    assertThat(record.path("status").asText()).isEqualTo("COMPLETED");

                    assertThat(elements(record.path("sourceMessageIds")))
                            .extracting(JsonNode::asLong)
                            .contains(messageId);
                });

        // 종료 후 거절된 투표 변경이 보고서 집계에 영향을 주면 안 된다.
        assertThat(records(content, "polls"))
                .anySatisfy(record -> {
                    assertThat(record.path("pollId").asLong()).isEqualTo(pollId);
                    assertThat(record.path("totalParticipants").asLong()).isEqualTo(1L);
                    assertThat(record.path("respondedParticipants").asLong()).isEqualTo(1L);
                    assertThat(record.path("unrespondedParticipants").asLong()).isZero();
                });

        assertThat(content.path("dataLimitations").toString())
                .contains("발언 기록이 없어");
    }

    private AudienceAnalysisService analysisService(AudienceAnalysisLlmClient client) {
        return new AudienceAnalysisService(
                analyses,
                audienceMessages,
                client,
                new AiAnalysisJsonParser(json),
                new AudienceAnalysisRetryProperties(0, Duration.ZERO, Duration.ofSeconds(5)),
                null,
                new AudienceAnalysisCostProperties(null, null),
                completions
        );
    }

    private AudienceAnalysisService emptyAnalysis() {
        return analysisService(messages ->
                new AudienceAnalysisLlmResponse("{\"topics\":[]}", "test-model"));
    }

    private JsonNode endAndGenerateReport(
            AudienceAnalysisService analysisService,
            List<Long> chatIds
    ) throws Exception {
        JsonNode ended = post(hostBrowser, roomPath() + "/end", Map.of(), 200);

        assertThat(ended.path("status").asText()).isEqualTo("ENDED");

        return generateReport(analysisService, chatIds);
    }

    private JsonNode generateReport(
            AudienceAnalysisService analysisService,
            List<Long> chatIds
    ) throws Exception {
        ObjectNode draft = json.createObjectNode();
        ObjectNode overview = draft.putObject("overview");

        overview.putArray("mainInterests");
        overview.putArray("reviewPoints");
        overview.putArray("suggestions");

        ObjectNode followUp = draft.putObject("followUp");

        chatIds.forEach(id -> followUp.withArray("reviewedChatIds").add(id));
        followUp.putArray("groups");

        when(reportOverviewClient.generate(any())).thenReturn(draft);

        JsonNode receipt = post(
                hostBrowser,
                roomPath() + "/reports",
                Map.of(),
                202
        );

        long reportId = receipt.path("reportId").asLong();

        assertThat(receipt.path("status").asText()).isEqualTo("PENDING");

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

        AiAnalysisFinalizationService finalizer = new AiAnalysisFinalizationService(
                finalizations,
                analyses,
                analysisService,
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

        assertThat(jdbc.sql("""
                        SELECT status
                        FROM ai_analysis_finalizations
                        WHERE lecture_room_id = ?
                        """)
                .param(roomId)
                .query(String.class)
                .single()).isEqualTo("COMPLETED");

        reportWorker.check(roomId);

        JsonNode report = get(hostBrowser, roomPath() + "/reports");

        assertThat(report.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(report.path("reportId").asLong()).isEqualTo(reportId);

        return report;
    }

    private Recording startRecording() throws Exception {
        String startKey = UUID.randomUUID().toString();

        JsonNode session = post(
                hostBrowser,
                roomPath() + "/stt/sessions",
                Map.of("startKey", startKey),
                201
        );

        assertThat(session.path("status").asText()).isEqualTo("RECORDING");

        return new Recording(
                session.path("sttSessionId").asLong(),
                startKey,
                session.path("serverTime").asText()
        );
    }

    private long saveSegment(
            Recording recording,
            String transcript,
            Instant time
    ) throws Exception {
        JsonNode segment = post(
                hostBrowser,
                roomPath() + "/stt/sessions/" + recording.sessionId() + "/segments",
                Map.of(
                        "startKey", recording.startKey(),
                        "segmentKey", UUID.randomUUID().toString(),
                        "transcript", transcript,
                        "segmentStartAt", time.toString(),
                        "segmentEndAt", time.toString()
                ),
                201
        );

        long segmentId = segment.path("segmentId").asLong();

        assertThat(segmentId).isPositive();

        return segmentId;
    }

    private JsonNode createUnderstandingPoll() throws Exception {
        return post(hostBrowser, roomPath() + "/polls", Map.of(
                "type", "UNDERSTANDING",
                "question", "설명 내용을 이해하셨나요?"
        ), 201);
    }

    private JsonNode respond(long pollId, long optionId, int expectedStatus) throws Exception {
        return request(
                audienceBrowser,
                "PUT",
                roomPath() + "/polls/" + pollId + "/responses/me",
                Map.of("optionId", optionId),
                expectedStatus
        );
    }

    private void closePoll(long pollId) throws Exception {
        JsonNode closed = post(
                hostBrowser,
                roomPath() + "/polls/" + pollId + "/close",
                Map.of(),
                200
        );

        assertThat(closed.path("status").asText()).isEqualTo("CLOSED");
    }

    private void assertPollCounts(
            long pollId,
            long total,
            long responded,
            long unresponded
    ) throws Exception {
        JsonNode result = get(
                hostBrowser,
                roomPath() + "/polls/" + pollId + "/results"
        );

        assertThat(result.path("totalParticipants").asLong()).isEqualTo(total);
        assertThat(result.path("respondedParticipants").asLong()).isEqualTo(responded);
        assertThat(result.path("unrespondedParticipants").asLong()).isEqualTo(unresponded);
    }

    private long exchangeChat(String content) throws Exception {
        String attempt = sendChat(content);

        long hostMessageId = receiveChat(hostMessages, attempt, content);
        long audienceMessageId = receiveChat(audienceChatMessages, attempt, content);

        assertThat(hostMessageId).isEqualTo(audienceMessageId);

        Long savedId = jdbc.sql("""
                        SELECT id
                        FROM chat_messages
                        WHERE lecture_room_id = ?
                          AND participant_id = ?
                          AND content = ?
                        """)
                .params(roomId, participantId, content)
                .query(Long.class)
                .single();

        assertThat(savedId).isEqualTo(hostMessageId);

        return hostMessageId;
    }

    private String sendChat(String content) {
        String attempt = UUID.randomUUID().toString();

        StompHeaders headers = new StompHeaders();
        headers.setDestination("/app/rooms/" + roomId + "/chat");
        headers.set("X-Chat-Attempt", attempt);
        headers.set("X-Chat-Role", "PARTICIPANT");

        audienceSession.send(headers, Map.of("content", content));

        return attempt;
    }

    private void assertEndedChatRejected() throws Exception {
        BlockingQueue<JsonNode> errors = subscribe(audienceSession, "/user/queue/errors");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (System.nanoTime() < deadline) {
            String attempt = sendChat("강연 종료 후 질문");

            JsonNode error = errors.poll(300, TimeUnit.MILLISECONDS);

            if (error != null
                    && error.path("type").asText().equals("ERROR")
                    && error.path("attempt").asText().equals(attempt)) {

                assertThat(error.path("code").asText()).isEqualTo("ROOM_ENDED");

                return;
            }
        }

        throw new AssertionError("강연 종료 후 채팅 거절 응답을 받지 못했다");
    }

    private long receiveChat(
            BlockingQueue<JsonNode> messages,
            String attempt,
            String content
    ) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (System.nanoTime() < deadline) {
            JsonNode event = messages.poll(100, TimeUnit.MILLISECONDS);

            if (event == null || !event.path("type").asText().equals("CHAT_BATCH")) {
                continue;
            }

            for (JsonNode message : event.path("data").path("messages")) {
                if (!message.path("attempt").asText().equals(attempt)) {
                    continue;
                }

                assertThat(message.path("content").asText()).isEqualTo(content);
                assertThat(message.path("roomId").asLong()).isEqualTo(roomId);
                assertThat(message.path("messageId").asLong()).isPositive();

                return message.path("messageId").asLong();
            }
        }

        throw new AssertionError("채팅 수신 실패: " + content);
    }

    private JsonNode awaitEvent(
            BlockingQueue<JsonNode> messages,
            String type,
            String attempt
    ) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (System.nanoTime() < deadline) {
            JsonNode event = messages.poll(100, TimeUnit.MILLISECONDS);

            if (event == null || !event.path("type").asText().equals(type)) {
                continue;
            }

            if (attempt == null || event.path("attempt").asText().equals(attempt)) {
                return event;
            }
        }

        throw new AssertionError("이벤트 수신 실패: " + type);
    }

    private long findTopicGroup(JsonNode aiResults, long messageId) {
        for (JsonNode analysis : aiResults.path("analyses")) {
            for (JsonNode group : analysis.path("topicGroups")) {
                for (JsonNode sourceId : group.path("sourceMessageIds")) {
                    if (sourceId.asLong() == messageId) {
                        return group.path("topicGroupId").asLong();
                    }
                }
            }
        }

        throw new AssertionError("청중 채팅과 연결된 AI 주제 그룹이 없다");
    }

    private StompSession connect(Browser browser, String role) throws Exception {
        String token = csrf(browser);

        String cookies = String.join("; ",
                browser.cookies().getCookieStore().getCookies().stream()
                        .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                        .toList());

        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        handshake.setOrigin("http://localhost:5173");
        handshake.add("Cookie", cookies);

        StompHeaders headers = new StompHeaders();
        headers.add("X-CSRF-TOKEN", token);
        headers.add("X-Chat-Role", role);

        return stompClient.connectAsync(
                "ws://localhost:" + port + "/ws",
                handshake,
                headers,
                new StompSessionHandlerAdapter() {
                }
        ).get(5, TimeUnit.SECONDS);
    }

    private BlockingQueue<JsonNode> subscribe(StompSession session, String topic) {
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

        return received;
    }

    private BlockingQueue<JsonNode> subscribeReady(
            StompSession session,
            String topic
    ) throws Exception {
        BlockingQueue<JsonNode> received = subscribe(session, topic);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (System.nanoTime() < deadline) {
            broker.convertAndSend(topic, Map.of("type", "WARMUP"));

            JsonNode event = received.poll(100, TimeUnit.MILLISECONDS);

            if (event != null && event.path("type").asText().equals("WARMUP")) {
                return received;
            }
        }

        throw new AssertionError("WebSocket 구독 실패: " + topic);
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
        return request(browser, "POST", path, body, expectedStatus).path("data");
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

    private JsonNode request(
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

        return json.readTree(response.body());
    }

    private List<JsonNode> records(JsonNode content, String field) {
        List<JsonNode> result = new ArrayList<>();

        for (JsonNode window : content.path("timeline")) {
            for (JsonNode record : window.path(field)) {
                result.add(record);
            }
        }

        return result;
    }

    private List<JsonNode> elements(JsonNode array) {
        List<JsonNode> result = new ArrayList<>();

        array.forEach(result::add);

        return result;
    }

    private long chatCount() {
        return jdbc.sql("""
                        SELECT COUNT(*)
                        FROM chat_messages
                        WHERE lecture_room_id = ?
                        """)
                .param(roomId)
                .query(Long.class)
                .single();
    }

    private void waitUntil(Instant target) throws InterruptedException {
        while (Instant.now().isBefore(target)) {
            long remaining = Duration.between(Instant.now(), target).toMillis();

            Thread.sleep(Math.max(1, Math.min(remaining, 200)));
        }
    }

    private void disconnect(StompSession session) {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private String roomPath() {
        return "/api/v1/rooms/" + roomId;
    }

    private String chatTopic() {
        return "/topic/rooms/" + roomId + "/chat";
    }

    private record Browser(HttpClient http, CookieManager cookies) {
    }

    private record Recording(long sessionId, String startKey, String serverTime) {
    }
}