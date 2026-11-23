package com.react.global.websocket;

import com.react.ai.domain.TopicCategory;
import com.react.ai.dto.AiAnalysisResult;
import com.react.ai.dto.AiTopicResult;
import com.react.ai.repository.AiAnalysisRepository;
import com.react.ai.service.AiAnalysisCompletionService;
import com.react.auth.entity.User;
import com.react.room.entity.LectureRoom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.stomp.StompSession;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AI 완료 저장부터 AFTER_COMMIT 방송, 실제 HOST STOMP 수신까지 검증한다. */
class AiAnalysisCompletedStompIntegrationTest extends StompIntegrationTestSupport {
    private static final String EVENT = "AI_ANALYSIS_COMPLETED";

    @Autowired AiAnalysisRepository analyses;
    @Autowired AiAnalysisCompletionService completions;

    private String hostEmail;
    private LectureRoom room;
    private long participantId;
    private StompSession hostSession;
    private BlockingQueue<Map<String, Object>> received;
    private final LocalDateTime start = LocalDateTime.of(2026, 11, 23, 10, 0);

    @BeforeEach
    void prepare() throws Exception {
        hostEmail = "host-" + UUID.randomUUID() + "@example.com";
        User host = newHost(hostEmail);
        room = newRoom(host, "AI 완료 이벤트 테스트");
        jdbc.sql("UPDATE lecture_rooms SET status = 'LIVE', started_at = ? WHERE id = ?")
                .params(start, room.getId()).update();
        participantId = participants.join(room.getJoinCode(), "청중", UUID.randomUUID().toString(), Map.of())
                .response().participantId();

        hostSession = client.connect(StompTestClient.ALLOWED_ORIGIN, loginCookies(hostEmail));
        received = subscribeReady(hostSession, aiTopic());
    }

    @AfterEach
    void disconnect() {
        if (hostSession != null && hostSession.isConnected()) {
            hostSession.disconnect();
        }
    }

    @Test
    void 완료_저장_커밋_뒤_HOST가_명세_AI_완료_이벤트를_수신한다() throws Exception {
        long messageId = insertAudienceMessage("Redis는 왜 사용하나요?", start);
        long analysisId = processingAnalysis(start, start.plusSeconds(30));

        completions.completeWithTopics(room.getId(), analysisId, new AiAnalysisResult(List.of(
                new AiTopicResult("Redis 사용 이유", "도입 이유에 대한 질문입니다.", TopicCategory.QUESTION,
                        List.of(messageId)))), "fake-model");
        long topicGroupId = jdbc.sql("SELECT id FROM ai_topic_groups WHERE analysis_id = ?")
                .param(analysisId).query(Long.class).single();

        Map<String, Object> event = StompTestClient.nextOfType(received, EVENT, 5000);
        assertThat(event).as("HOST가 AI 완료 이벤트를 수신해야 한다").isNotNull();
        assertThat(event.get("type")).isEqualTo(EVENT);

        Map<String, Object> data = data(event);
        assertThat(((Number) data.get("analysisId")).longValue()).isEqualTo(analysisId);
        assertThat(((Number) data.get("roomId")).longValue()).isEqualTo(room.getId());
        assertThat(data.get("completedAt")).isInstanceOf(String.class);
        assertThat((String) data.get("completedAt")).isNotBlank();
        Map<String, Object> group = topicGroups(data).getFirst();
        assertThat(((Number) group.get("topicGroupId")).longValue()).isEqualTo(topicGroupId);
        assertThat(group).containsEntry("topic", "Redis 사용 이유")
                .containsEntry("category", "QUESTION")
                .containsEntry("summary", "도입 이유에 대한 질문입니다.")
                .containsEntry("messageCount", 1);
    }

    @Test
    void 빈_구간_완료도_빈_주제_목록으로_HOST에게_전달한다() throws Exception {
        long analysisId = processingAnalysis(start, start.plusSeconds(30));

        assertThat(completions.completeEmpty(room.getId(), analysisId)).isTrue();

        Map<String, Object> event = StompTestClient.nextOfType(received, EVENT, 5000);
        assertThat(event).isNotNull();
        Map<String, Object> data = data(event);
        assertThat(((Number) data.get("analysisId")).longValue()).isEqualTo(analysisId);
        assertThat(data.get("topicGroups")).isEqualTo(List.of());
    }

    @Test
    void 완료_저장이_롤백되면_HOST에게_완료_이벤트를_전달하지_않는다() throws Exception {
        long messageId = insertAudienceMessage("중복 연결 검증", start);
        long analysisId = processingAnalysis(start, start.plusSeconds(30));
        AiAnalysisResult invalid = new AiAnalysisResult(List.of(
                new AiTopicResult("Redis", "중복 원문", TopicCategory.QUESTION, List.of(messageId, messageId))));

        assertThatThrownBy(() -> completions.completeWithTopics(room.getId(), analysisId, invalid, "fake-model"))
                .isInstanceOf(RuntimeException.class);

        assertThat(StompTestClient.nextOfType(received, EVENT, 700))
                .as("롤백된 분석은 AI 완료 이벤트를 전송하지 않는다")
                .isNull();
    }

    private BlockingQueue<Map<String, Object>> subscribeReady(StompSession session, String topic)
            throws InterruptedException {
        var queue = client.subscribe(session, topic);
        assertThat(StompTestClient.awaitBroadcast(queue,
                () -> broker.convertAndSend(topic, Map.of("type", "WARMUP"))))
                .as("HOST AI 구독이 등록되어야 한다")
                .isNotNull();
        return queue;
    }

    private long processingAnalysis(LocalDateTime windowStart, LocalDateTime windowEnd) {
        var acquired = analyses.acquire(room.getId(), windowStart, windowEnd);
        assertThat(analyses.markProcessing(acquired.analysis().id())).isTrue();
        return acquired.analysis().id();
    }

    private long insertAudienceMessage(String content, LocalDateTime receivedAt) {
        jdbc.sql("""
                INSERT INTO chat_messages(lecture_room_id, participant_id, content, received_at)
                VALUES (?, ?, ?, ?)
                """).params(room.getId(), participantId, content, receivedAt).update();
        return jdbc.sql("SELECT id FROM chat_messages WHERE lecture_room_id = ? ORDER BY id DESC LIMIT 1")
                .param(room.getId()).query(Long.class).single();
    }

    private String aiTopic() {
        return "/topic/rooms/" + room.getId() + "/ai";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> event) {
        return (Map<String, Object>) event.get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> topicGroups(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("topicGroups");
    }

}
