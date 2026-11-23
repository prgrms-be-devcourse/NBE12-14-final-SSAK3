package com.react.ai;

import com.react.ai.domain.TopicCategory;
import com.react.ai.dto.AiAnalysisResult;
import com.react.ai.dto.AiTopicResult;
import com.react.ai.event.AiAnalysisCompletedDomainEvent;
import com.react.ai.repository.AiAnalysisRepository;
import com.react.ai.service.AiAnalysisCompletionService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(AiAnalysisCompletionEventIntegrationTest.ProbeConfiguration.class)
class AiAnalysisCompletionEventIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired AiAnalysisRepository analyses;
    @Autowired AiAnalysisCompletionService completions;
    @Autowired CommittedAnalysisProbe probe;

    private final LocalDateTime start = LocalDateTime.of(2026, 11, 23, 10, 0);
    private final LocalDateTime end = start.plusSeconds(30);
    private long roomId;
    private long messageId;

    @BeforeEach
    void prepare() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        probe.clear();

        String key = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO users(email, password_hash, name, created_at) VALUES (?, 'hash', 'host', NOW())")
                .param(key + "@example.com").update();
        long hostId = jdbc.sql("SELECT id FROM users WHERE email = ?").param(key + "@example.com")
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO lecture_rooms(host_user_id, title, join_code, status, created_at, started_at)
                VALUES (?, '강연', ?, 'LIVE', NOW(), ?)
                """).params(hostId, key, start).update();
        roomId = jdbc.sql("SELECT id FROM lecture_rooms WHERE join_code = ?").param(key).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO lecture_participants(lecture_room_id, nickname, participant_token_hash, joined_at)
                VALUES (?, '청중', ?, NOW())
                """).params(roomId, key + "-participant").update();
        long participantId = jdbc.sql("SELECT id FROM lecture_participants WHERE participant_token_hash = ?")
                .param(key + "-participant").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO chat_messages(lecture_room_id, participant_id, content, received_at)
                VALUES (?, ?, '질문', ?)
                """).params(roomId, participantId, start).update();
        messageId = jdbc.sql("SELECT id FROM chat_messages WHERE lecture_room_id = ? ORDER BY id DESC LIMIT 1")
                .param(roomId).query(Long.class).single();
    }

    @Test
    void 완료_저장_트랜잭션이_커밋된_뒤에만_이벤트_리스너가_완료_행과_그룹을_조회한다() {
        long analysisId = processingAnalysis();
        AiAnalysisResult result = new AiAnalysisResult(List.of(
                new AiTopicResult("Redis", "도입 이유 질문", TopicCategory.QUESTION, List.of(messageId))));

        completions.completeWithTopics(roomId, analysisId, result, "fake-model");

        assertThat(probe.events()).containsExactly(new CommittedAnalysisProbe.Observed(roomId, analysisId,
                "COMPLETED", 1L));
    }

    @Test
    void 완료_저장이_롤백되면_AFTER_COMMIT_이벤트는_발행되지_않는다() {
        long analysisId = processingAnalysis();
        AiAnalysisResult invalid = new AiAnalysisResult(List.of(
                new AiTopicResult("Redis", "중복 연결", TopicCategory.QUESTION, List.of(messageId, messageId))));

        assertThatThrownBy(() -> completions.completeWithTopics(roomId, analysisId, invalid, "fake-model"))
                .isInstanceOf(RuntimeException.class);

        assertThat(probe.events()).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_topic_groups WHERE analysis_id = ?").param(analysisId)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT status FROM ai_analyses WHERE id = ?").param(analysisId)
                .query(String.class).single()).isEqualTo("PROCESSING");
    }

    private long processingAnalysis() {
        var acquired = analyses.acquire(roomId, start, end);
        assertThat(analyses.markProcessing(acquired.analysis().id())).isTrue();
        return acquired.analysis().id();
    }

    @TestConfiguration
    static class ProbeConfiguration {
        @Bean
        CommittedAnalysisProbe committedAnalysisProbe(JdbcClient jdbc) {
            return new CommittedAnalysisProbe(jdbc);
        }
    }

    static class CommittedAnalysisProbe {
        private final JdbcClient jdbc;
        private final List<Observed> events = new ArrayList<>();

        CommittedAnalysisProbe(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onCompleted(AiAnalysisCompletedDomainEvent event) {
            String status = jdbc.sql("SELECT status FROM ai_analyses WHERE id = ?")
                    .param(event.analysisId()).query(String.class).single();
            long groups = jdbc.sql("SELECT COUNT(*) FROM ai_topic_groups WHERE analysis_id = ?")
                    .param(event.analysisId()).query(Long.class).single();
            events.add(new Observed(event.roomId(), event.analysisId(), status, groups));
        }

        void clear() {
            events.clear();
        }

        List<Observed> events() {
            return List.copyOf(events);
        }

        record Observed(long roomId, long analysisId, String status, long topicGroups) {
        }
    }
}
