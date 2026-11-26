package com.react.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.report.service.ReportOverviewClient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportReadApiTest {
    private static final String REPORT = "/api/v1/rooms/{roomId}/reports";
    private static final String STATUS = REPORT + "/status";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;
    @MockitoBean ReportOverviewClient llm;
    private String owner;
    private long roomId;

    @BeforeAll
    void migrateTestDatabase() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).isEqualTo("react_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @BeforeEach
    void createEndedRoom() {
        owner = UUID.randomUUID() + "@example.com";
        jdbc.sql("INSERT INTO users(email,password_hash,name,created_at) VALUES (?, 'hash','host',NOW())")
                .param(owner).update();
        roomId = createRoom();
    }

    @AfterEach
    void readsDoNotCallTheModel() {
        verifyNoInteractions(llm);
    }

    private long createRoom() {
        String code = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at,started_at,ended_at)
                SELECT id,'조회 테스트',?,'ENDED',NOW(),NOW(),NOW() FROM users WHERE email=?
                """).params(code, owner).update();
        return jdbc.sql("SELECT id FROM lecture_rooms WHERE join_code=?").param(code).query(Long.class).single();
    }

    private JsonNode read(String path, long room) throws Exception {
        var response = mvc.perform(get(path, room).with(user(owner).roles("HOST")))
                .andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray()).required("data");
    }

    @Test
    void completedStatusIncludesOnlyPublicRecoveryInformation() throws Exception {
        jdbc.sql("""
                INSERT INTO lecture_reports(lecture_room_id,status,content,created_at,updated_at,generated_at)
                VALUES (?,'COMPLETED',JSON_OBJECT('timeline',JSON_ARRAY()),NOW(),NOW(),NOW())
                """).param(roomId).update();

        assertThat(read(STATUS, roomId)).isEqualTo(json.readTree("""
                {"status":"COMPLETED","retryAvailable":false,"delayed":false,"failureCategory":null}
                """));
    }

    @Test
    void readsTheStoredJsonAndTimestampsForTheSelectedRoomOnEachVisit() throws Exception {
        String content = """
                {"overview":{"mainInterests":[],"reviewPoints":[],"suggestions":[]},
                 "timeline":[], "dataLimitations":["채팅 분석에 실패한 구간이 있습니다."],
                 "evidencePolicy":"저장된 원문과 집계를 기준으로 합니다."}
                """;
        long otherRoom = createRoom();
        saveCompleted(otherRoom, "{\"timeline\":[],\"dataLimitations\":[\"다른 강연 기록\"]}");
        long reportId = saveCompleted(roomId, content);

        var result = read(REPORT, roomId);
        assertThat(result.required("reportId").asLong()).isEqualTo(reportId);
        assertThat(result.required("roomId").asLong()).isEqualTo(roomId);
        assertThat(result.required("status").asText()).isEqualTo("COMPLETED");
        assertThat(result.required("createdAt").asText()).isEqualTo("2026-11-17T02:00:10Z");
        assertThat(result.required("generatedAt").asText()).isEqualTo("2026-11-17T02:00:30Z");
        assertThat(result.required("content")).isEqualTo(json.readTree(content));
        assertThat(read(REPORT, roomId)).isEqualTo(result);
    }

    private long saveCompleted(long room, String content) {
        jdbc.sql("""
                INSERT INTO lecture_reports(lecture_room_id,status,content,created_at,updated_at,generated_at)
                VALUES (?,'COMPLETED',?,'2026-11-17 11:00:10','2026-11-17 11:00:30','2026-11-17 11:00:30')
                """).params(room, content).update();
        return jdbc.sql("SELECT id FROM lecture_reports WHERE lecture_room_id=?").param(room).query(Long.class).single();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PROCESSING", "FAILED"})
    void unfinishedReportsOnlyExposeTheirStateAndPublicFailureMessage(String reportStatus) throws Exception {
        jdbc.sql("""
                INSERT INTO lecture_reports(lecture_room_id,status,content,error_message,created_at,updated_at)
                VALUES (?,?,JSON_OBJECT('partial','공개되지 않을 내용'),'INVALID_REPORT_EVIDENCE',
                        UTC_TIMESTAMP() + INTERVAL 9 HOUR, UTC_TIMESTAMP() + INTERVAL 9 HOUR)
                """).params(roomId, reportStatus).update();

        assertThat(read(STATUS, roomId)).isEqualTo(json.createObjectNode().put("status", reportStatus)
                .put("retryAvailable", false).put("delayed", false)
                .put("failureCategory", reportStatus.equals("FAILED") ? "AI_OUTPUT" : null));
        var result = read(REPORT, roomId);
        assertThat(result.required("status").asText()).isEqualTo(reportStatus);
        assertThat(result.required("content").isNull()).isTrue();
        assertThat(result.toString()).doesNotContain("INVALID_REPORT_EVIDENCE", "공개되지 않을 내용");
        if (reportStatus.equals("FAILED")) {
            assertThat(result.required("message").asText()).isEqualTo("보고서 생성에 실패했습니다.");
        } else {
            assertThat(result.required("generatedAt").isNull()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PROCESSING"})
    void delayUsesDatabaseTimeAndTheStartOfTheCurrentState(String state) throws Exception {
        jdbc.sql("""
                INSERT INTO lecture_reports(lecture_room_id,status,created_at)
                VALUES (?,?, UTC_TIMESTAMP() + INTERVAL 9 HOUR - INTERVAL 120 SECOND)
                """).params(roomId, state).update();
        assertThat(read(STATUS, roomId).required("delayed").asBoolean()).isTrue();
        jdbc.sql("UPDATE lecture_reports SET updated_at=UTC_TIMESTAMP() + INTERVAL 9 HOUR WHERE lecture_room_id=?")
                .param(roomId).update();
        assertThat(read(STATUS, roomId).required("delayed").asBoolean()).isFalse();
    }

    @Test
    void unknownDispatchOffersOneRetryAndWaitsForALateResultWithoutDeclaringFailure() throws Exception {
        jdbc.sql("""
                INSERT INTO lecture_reports(lecture_room_id,status,error_message,created_at)
                VALUES (?,'FAILED','REPORT_DISPATCH_UNKNOWN',NOW())
                """).param(roomId).update();
        var unconfirmed = json.createObjectNode().put("status", "FAILED").put("retryAvailable", true)
                .put("delayed", false).put("failureCategory", "UNCONFIRMED");
        assertThat(read(STATUS, roomId)).isEqualTo(unconfirmed);
        assertThat(read(REPORT, roomId).required("message").asText()).isEqualTo("보고서 생성 요청의 처리 결과를 확인하고 있습니다.");
        jdbc.sql("UPDATE lecture_reports SET retry_used=TRUE WHERE lecture_room_id=?").param(roomId).update();
        assertThat(read(STATUS, roomId)).isEqualTo(unconfirmed.put("retryAvailable", false));
        jdbc.sql("UPDATE lecture_reports SET status='COMPLETED',error_message=NULL WHERE lecture_room_id=?").param(roomId).update();
        assertThat(read(STATUS, roomId)).isEqualTo(json.createObjectNode().put("status", "COMPLETED")
                .put("retryAvailable", false).put("delayed", false).putNull("failureCategory"));
    }

    @Test
    void expiredDispatchClosesResultRecoveryAndPreservesOnlyAnUnspentRetry() throws Exception {
        jdbc.sql("""
                INSERT INTO lecture_reports(lecture_room_id,status,error_message,created_at)
                VALUES (?,'FAILED','REPORT_DISPATCH_EXPIRED',NOW())
                """).param(roomId).update();
        var expired = json.createObjectNode().put("status", "FAILED").put("retryAvailable", true)
                .put("delayed", false).put("failureCategory", "RECOVERY_EXPIRED");
        assertThat(read(STATUS, roomId)).isEqualTo(expired);
        assertThat(read(REPORT, roomId).required("message").asText()).isEqualTo("보고서 생성 요청의 결과를 확인할 수 있는 기간이 지났습니다.");
        jdbc.sql("UPDATE lecture_reports SET retry_used=TRUE WHERE lecture_room_id=?").param(roomId).update();
        assertThat(read(STATUS, roomId)).isEqualTo(expired.put("retryAvailable", false));
    }

    @Test
    void onlyTheUnspentTransientFailureShowsTheRetryAction() throws Exception {
        jdbc.sql("""
                INSERT INTO lecture_reports(lecture_room_id,status,error_message,created_at)
                VALUES (?,'FAILED','LLM_TEMPORARY_FAILURE','2025-01-01')
                """).param(roomId).update();
        assertThat(read(STATUS, roomId)).isEqualTo(json.readTree("""
                {"status":"FAILED","retryAvailable":true,"delayed":false,"failureCategory":"AI_SERVICE"}
                """));
        jdbc.sql("UPDATE lecture_reports SET retry_used=TRUE WHERE lecture_room_id=?").param(roomId).update();
        assertThat(read(STATUS, roomId)).isEqualTo(json.readTree("""
                {"status":"FAILED","retryAvailable":false,"delayed":false,"failureCategory":"AI_SERVICE"}
                """));
    }

    @ParameterizedTest
    @CsvSource({
            "LLM_REQUEST_FAILED, AI_SERVICE",
            "INVALID_REPORT_FORMAT, AI_OUTPUT",
            "INVALID_REPORT_EVIDENCE, AI_OUTPUT",
            "GENERATION_INTERRUPTED, REPORT",
            "REPORT_STORAGE_FAILED, REPORT",
            "sensitive legacy provider error detail, REPORT",
            ", REPORT"
    })
    void failuresExposeOnlyTheirPublicCategory(String internalReason, String category) throws Exception {
        jdbc.sql("""
                INSERT INTO lecture_reports(lecture_room_id,status,error_message,created_at)
                VALUES (?,'FAILED',?,NOW())
                """).params(roomId, internalReason).update();
        assertThat(read(STATUS, roomId)).isEqualTo(json.createObjectNode()
                .put("status", "FAILED").put("retryAvailable", false).put("delayed", false)
                .put("failureCategory", category));
    }

    @ParameterizedTest
    @ValueSource(strings = {STATUS, REPORT})
    void distinguishesMissingRoomMissingReportAndNonEndedRoomWithoutSubmitting(String path) throws Exception {
        var host = user(owner).roles("HOST");
        mvc.perform(get(path, -1).with(host)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
        mvc.perform(get(path, roomId).with(host)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
        for (String activeStatus : new String[]{"READY", "LIVE"}) {
            jdbc.sql("UPDATE lecture_rooms SET status=?, ended_at=NULL WHERE id=?").params(activeStatus, roomId).update();
            mvc.perform(get(path, roomId).with(host)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ROOM_NOT_ENDED"))
                    .andExpect(jsonPath("$.message").value("강연이 끝나면 회고 보고서가 자동으로 생성됩니다."));
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lecture_reports WHERE lecture_room_id=?")
                .param(roomId).query(Long.class).single()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {STATUS, REPORT})
    void requiresTheHostRoleAndRoomOwnershipBeforeDisclosingReportAvailability(String path) throws Exception {
        mvc.perform(get(path, roomId)).andExpect(status().isUnauthorized());
        mvc.perform(get(path, roomId).with(user(owner).roles("PARTICIPANT"))).andExpect(status().isForbidden());
        var differentHost = user("other@example.com").roles("HOST");
        mvc.perform(get(path, roomId).with(differentHost)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        saveCompleted(roomId, "{\"timeline\":[]}");
        mvc.perform(get(path, roomId).with(differentHost)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
