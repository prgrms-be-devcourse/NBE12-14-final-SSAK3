package com.react.ai.service;

import com.react.ai.domain.AiAnalysisFinalizationStatus;
import com.react.ai.dto.AudienceAnalysisLlmResponse;
import com.react.ai.repository.AiAnalysisFinalizationRepository;
import com.react.ai.repository.AiAnalysisRepository;
import com.react.ai.repository.AudienceMessageRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AiAnalysisFinalizationIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired AiAnalysisFinalizationRepository finalizations;
    @Autowired AiAnalysisRepository analyses;
    @Autowired AudienceMessageRepository messages;
    @Autowired AiAnalysisJsonParser parser;

    private AiAnalysisFinalizationRepository.EndedRoom room;

    @BeforeEach
    void prepareDatabase() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();

        String key = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO users(email, password_hash, name, created_at) VALUES (?, 'hash', 'host', NOW())")
                .param(key + "@example.com").update();
        long hostId = jdbc.sql("SELECT id FROM users WHERE email = ?").param(key + "@example.com")
                .query(Long.class).single();
        LocalDateTime startedAt = LocalDateTime.of(2026, 11, 18, 10, 0);
        LocalDateTime endedAt = startedAt.plusSeconds(15);
        jdbc.sql("""
                INSERT INTO lecture_rooms(host_user_id, title, join_code, status, created_at, started_at, ended_at)
                VALUES (?, '빈 종료 강연', ?, 'ENDED', NOW(), ?, ?)
                """).params(hostId, key, startedAt, endedAt).update();
        long roomId = jdbc.sql("SELECT id FROM lecture_rooms WHERE join_code = ?").param(key).query(Long.class).single();
        room = new AiAnalysisFinalizationRepository.EndedRoom(roomId, startedAt, endedAt);
    }

    @Test
    void 빈_잔여_구간은_Gemini_호출_없이_완료되고_finalization도_완료된다() {
        AtomicInteger llmCalls = new AtomicInteger();
        AudienceAnalysisService audience = new AudienceAnalysisService(analyses, messages, candidates -> {
            llmCalls.incrementAndGet();
            return new AudienceAnalysisLlmResponse("{\"topics\":[]}", "test");
        }, parser);
        AiAnalysisFinalizationService service = new AiAnalysisFinalizationService(
                finalizations, analyses, audience, new AiAnalysisWindowCalculator());

        service.processRoom(room, 3, Duration.ofSeconds(90));

        assertThat(llmCalls).hasValue(0);
        assertThat(jdbc.sql("SELECT status FROM ai_analyses WHERE lecture_room_id = ?")
                .param(room.roomId()).query(String.class).single()).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT model_name FROM ai_analyses WHERE lecture_room_id = ?")
                .param(room.roomId()).query(String.class).optional()).isEmpty();
        assertThat(finalizations.find(room.roomId()).status()).isEqualTo(AiAnalysisFinalizationStatus.COMPLETED);
    }

    @Test
    void 시작과_종료_시각이_같은_빈_강연은_Gemini와_분석을_호출하지_않고_완료된다() {
        jdbc.sql("UPDATE lecture_rooms SET ended_at = started_at WHERE id = ?")
                .param(room.roomId()).update();
        AiAnalysisFinalizationRepository.EndedRoom emptyRoom =
                new AiAnalysisFinalizationRepository.EndedRoom(room.roomId(), room.startedAt(), room.startedAt());
        AtomicInteger llmCalls = new AtomicInteger();
        AudienceAnalysisService audience = new AudienceAnalysisService(analyses, messages, candidates -> {
            llmCalls.incrementAndGet();
            return new AudienceAnalysisLlmResponse("{\"topics\":[]}", "test");
        }, parser);
        AiAnalysisFinalizationService service = new AiAnalysisFinalizationService(
                finalizations, analyses, audience, new AiAnalysisWindowCalculator());

        service.processRoom(emptyRoom, 3, Duration.ofSeconds(90));

        assertThat(llmCalls).hasValue(0);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_analyses WHERE lecture_room_id = ?")
                .param(room.roomId()).query(Integer.class).single()).isZero();
        var finalization = finalizations.find(room.roomId());
        assertThat(finalization.status()).isEqualTo(AiAnalysisFinalizationStatus.COMPLETED);
        assertThat(finalization.failedAnalysisCount()).isZero();
    }
}
