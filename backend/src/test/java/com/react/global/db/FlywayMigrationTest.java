package com.react.global.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// test 프로파일은 Flyway 를 끄므로, 여기서 직접 실행해 V1 이 ERD 대로 만들어지는지 검증한다.
// 테스트 DB 의 모든 객체를 지우므로 DB 이름이 _test 로 끝날 때만 실행한다.
@SpringBootTest
@ActiveProfiles("test")
class FlywayMigrationTest {

    private static final List<String> EXPECTED_TABLES = List.of(
            "users", "lecture_rooms", "lecture_participants", "chat_messages",
            "polls", "poll_options", "poll_responses",
            "stt_sessions", "stt_segments", "stt_gaps",
            "ai_analyses", "ai_topic_groups", "ai_topic_group_messages",
            "ai_analysis_finalizations",
            "ai_llm_call_usages",
            "lecture_reports");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    private Flyway flyway;

    @BeforeEach
    void migrate() {
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        assertThat(database).as("마이그레이션 테스트는 테스트 DB 에서만 실행한다").endsWith("_test");

        flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    @AfterEach
    void cleanUp() {
        flyway.clean();
    }

    @Test
    void ERD의_16개_테이블이_생성된다() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'",
                String.class);

        assertThat(tables).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);
    }

    @Test
    void 채팅_작성자는_청중과_강연자_중_정확히_하나다() {
        long hostId = insertUser("host@example.com");
        long roomId = insertRoom(hostId, "CODE01");
        long participantId = insertParticipant(roomId, "token-1");

        // MySQL CHECK 위반(3819)은 Spring 이 DataIntegrityViolationException 으로 변환하지 않는다.
        assertThatThrownBy(() -> insertChat(roomId, null, null))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_chat_messages_sender");
        assertThatThrownBy(() -> insertChat(roomId, participantId, hostId))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_chat_messages_sender");
        assertThatCode(() -> insertChat(roomId, participantId, null)).doesNotThrowAnyException();
        assertThatCode(() -> insertChat(roomId, null, hostId)).doesNotThrowAnyException();
    }

    @Test
    void 참여_코드와_참여자_토큰은_중복될_수_없다() {
        long hostId = insertUser("host@example.com");
        long roomId = insertRoom(hostId, "CODE01");
        insertParticipant(roomId, "token-1");

        assertThatThrownBy(() -> insertRoom(hostId, "CODE01"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertParticipant(roomId, "token-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 한_참여자는_투표에_한_번만_응답할_수_있다() {
        long hostId = insertUser("host@example.com");
        long roomId = insertRoom(hostId, "CODE01");
        long participantId = insertParticipant(roomId, "token-1");
        long pollId = insertPoll(roomId);
        long optionId = insertPollOption(pollId, 1);
        long otherOptionId = insertPollOption(pollId, 2);

        assertThatCode(() -> insertPollResponse(pollId, optionId, participantId)).doesNotThrowAnyException();
        assertThatThrownBy(() -> insertPollResponse(pollId, otherOptionId, participantId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertPollOption(pollId, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 보고서는_강연방당_하나만_둘_수_있다() {
        long hostId = insertUser("host@example.com");
        long roomId = insertRoom(hostId, "CODE01");

        jdbc.update("INSERT INTO lecture_reports(lecture_room_id, status, created_at) VALUES (?, 'PENDING', NOW())", roomId);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO lecture_reports(lecture_room_id, status, created_at) "
                + "VALUES (?, 'PENDING', NOW())", roomId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void STT_세그먼트_키와_AI_분석_구간은_중복될_수_없다() {
        long hostId = insertUser("host@example.com");
        long roomId = insertRoom(hostId, "CODE01");
        long sessionId = insertAndGetId(
                "INSERT INTO stt_sessions(lecture_room_id, status, started_at) VALUES (?, 'RECORDING', NOW())", roomId);

        String insertSegment = "INSERT INTO stt_segments(stt_session_id, segment_key, transcript, segment_start_at, "
                + "segment_end_at, received_at) VALUES (?, 'seg-1', '발언', NOW(), NOW(), NOW())";
        jdbc.update(insertSegment, sessionId);
        assertThatThrownBy(() -> jdbc.update(insertSegment, sessionId))
                .isInstanceOf(DataIntegrityViolationException.class);

        String insertAnalysis = "INSERT INTO ai_analyses(lecture_room_id, window_start_at, window_end_at, status, created_at) "
                + "VALUES (?, '2026-11-17 10:00:00', '2026-11-17 10:00:30', 'PENDING', NOW())";
        jdbc.update(insertAnalysis, roomId);
        assertThatThrownBy(() -> jdbc.update(insertAnalysis, roomId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void AI_호출_이력은_필수_컬럼과_분석_FK와_시도별_UNIQUE_제약을_가진다() {
        List<String> requiredColumns = List.of(
                "id", "analysis_id", "attempt_number", "model_name", "call_status", "error_code",
                "prompt_token_count", "output_token_count", "total_token_count", "duration_ms",
                "input_usd_per_million_tokens", "output_usd_per_million_tokens",
                "estimated_cost_microusd", "created_at");
        List<String> columns = jdbc.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'ai_llm_call_usages'
                """, String.class);
        assertThat(columns).containsExactlyInAnyOrderElementsOf(requiredColumns);

        long hostId = insertUser("usage-host@example.com");
        long roomId = insertRoom(hostId, "USAGE01");
        long analysisId = insertAndGetId("""
                INSERT INTO ai_analyses(lecture_room_id, window_start_at, window_end_at, status, created_at)
                VALUES (?, '2026-11-17 10:00:00', '2026-11-17 10:00:30', 'PENDING', NOW())
                """, roomId);
        String insertUsage = """
                INSERT INTO ai_llm_call_usages(analysis_id, attempt_number, call_status, duration_ms, created_at)
                VALUES (?, ?, 'FAILED', 0, NOW())
                """;

        assertThatThrownBy(() -> jdbc.update(insertUsage, 999_999L, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> jdbc.update(insertUsage, analysisId, 1)).doesNotThrowAnyException();
        assertThatThrownBy(() -> jdbc.update(insertUsage, analysisId, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 존재하지_않는_강연방을_참조할_수_없다() {
        assertThatThrownBy(() -> insertParticipant(999_999L, "token-x"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long insertUser(String email) {
        return insertAndGetId("INSERT INTO users(email, password_hash, name, created_at) "
                + "VALUES (?, 'hash', '강연자', NOW())", email);
    }

    private long insertRoom(long hostId, String joinCode) {
        return insertAndGetId("INSERT INTO lecture_rooms(host_user_id, title, join_code, status, created_at) "
                + "VALUES (?, '강연', ?, 'READY', NOW())", hostId, joinCode);
    }

    private long insertParticipant(long roomId, String tokenHash) {
        return insertAndGetId("INSERT INTO lecture_participants(lecture_room_id, nickname, participant_token_hash, joined_at) "
                + "VALUES (?, '청중', ?, NOW())", roomId, tokenHash);
    }

    private void insertChat(long roomId, Long participantId, Long hostId) {
        jdbc.update("INSERT INTO chat_messages(lecture_room_id, participant_id, host_user_id, content, received_at) "
                + "VALUES (?, ?, ?, '메시지', NOW())", roomId, participantId, hostId);
    }

    private long insertPoll(long roomId) {
        return insertAndGetId("INSERT INTO polls(lecture_room_id, question, status, created_at, closes_at) "
                + "VALUES (?, '이해했나요?', 'OPEN', NOW(), NOW() + INTERVAL 60 SECOND)", roomId);
    }

    private long insertPollOption(long pollId, int displayOrder) {
        return insertAndGetId("INSERT INTO poll_options(poll_id, content, display_order) VALUES (?, '선택지', ?)",
                pollId, displayOrder);
    }

    private void insertPollResponse(long pollId, long optionId, long participantId) {
        jdbc.update("INSERT INTO poll_responses(poll_id, poll_option_id, participant_id, responded_at) "
                + "VALUES (?, ?, ?, NOW())", pollId, optionId, participantId);
    }

    // LAST_INSERT_ID() 는 커넥션마다 다르므로 JDBC 로 생성된 키를 직접 받는다.
    private long insertAndGetId(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }
}
