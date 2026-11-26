package com.react.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportRequestApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    private String owner;
    private long room;

    @BeforeAll
    void migrate() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @BeforeEach
    void endedRoom() {
        String key = UUID.randomUUID().toString();
        owner = key + "@example.com";
        jdbc.sql("INSERT INTO users(email,password_hash,name,created_at) VALUES (?, 'hash','host',NOW())")
                .param(owner).update();
        jdbc.sql("""
                INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at,started_at,ended_at)
                SELECT id,'test',?,'ENDED',NOW(),NOW(),NOW() FROM users WHERE email=?
                """).params(key, owner).update();
        room = jdbc.sql("SELECT id FROM lecture_rooms WHERE join_code=?").param(key).query(Long.class).single();
    }

    @Test
    void ownerReceivesSameReportAndItsCurrentStatusOnRepeatedRequests() throws Exception {
        var first = mvc.perform(request(room).with(user(owner).roles("HOST")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.roomId").value(room))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.createdAt").isString())
                .andReturn().getResponse();
        long id = json.readTree(first.getContentAsByteArray()).at("/data/reportId").longValue();
        for (String state : new String[]{"PENDING", "PROCESSING", "COMPLETED", "FAILED"}) {
            jdbc.sql("UPDATE lecture_reports SET status=? WHERE id=?").params(state, id).update();
            boolean active = state.equals("PENDING") || state.equals("PROCESSING");
            mvc.perform(request(room).with(user(owner).roles("HOST")))
                    .andExpect(active ? status().isAccepted() : status().isOk())
                    .andExpect(jsonPath("$.data.reportId").value(id))
                    .andExpect(jsonPath("$.data.status").value(state));
        }
    }

    @Test
    void onlyTheOwnerOfAnEndedRoomMayReceiveAReport() throws Exception {
        mvc.perform(request(room))
                .andExpect(status().isUnauthorized());
        mvc.perform(request(room).with(user(owner).roles("PARTICIPANT")))
                .andExpect(status().isForbidden());
        mvc.perform(request(room).with(user("other@example.com").roles("HOST")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(request(-1).with(user(owner).roles("HOST")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lecture_reports WHERE lecture_room_id=?")
                .param(room).query(Integer.class).single()).isZero();
    }

    @Test
    void aLiveRoomRejectsReceiptWithoutCreatingAReport() throws Exception {
        jdbc.sql("UPDATE lecture_rooms SET status='LIVE', ended_at=NULL WHERE id=?").param(room).update();
        mvc.perform(request(room).with(user(owner).roles("HOST")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROOM_NOT_ENDED"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lecture_reports WHERE lecture_room_id=?")
                .param(room).query(Integer.class).single()).isZero();
    }

    private MockHttpServletRequestBuilder request(long roomId) {
        return post("/api/v1/rooms/{id}/reports", roomId).with(csrf());
    }

    @Test
    void retryRequiresRoomOwnershipAndAnExistingReport() throws Exception {
        String path = "/api/v1/rooms/{id}/reports/retry";
        mvc.perform(post(path, room).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(path, room).with(csrf()).with(user(owner).roles("PARTICIPANT")))
                .andExpect(status().isForbidden());
        mvc.perform(post(path, room).with(csrf()).with(user("other@example.com").roles("HOST")))
                .andExpect(status().isForbidden());
        mvc.perform(post(path, room).with(user(owner).roles("HOST"))).andExpect(status().isForbidden());
        mvc.perform(post(path, room).with(csrf()).with(user(owner).roles("HOST")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
    }

    @Test
    void retryRejectsALiveRoomBeforeLookingUpItsReport() throws Exception {
        jdbc.sql("UPDATE lecture_rooms SET status='LIVE', ended_at=NULL WHERE id=?").param(room).update();
        mvc.perform(post("/api/v1/rooms/{id}/reports/retry", room).with(csrf()).with(user(owner).roles("HOST")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROOM_NOT_ENDED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"LLM_TEMPORARY_FAILURE", "REPORT_DISPATCH_UNKNOWN", "REPORT_DISPATCH_EXPIRED"})
    void retryOnlyRequeuesAnEligibleFailureOnce(String eligibleReason) throws Exception {
        String path = "/api/v1/rooms/{id}/reports/retry";
        jdbc.sql("INSERT INTO lecture_reports(lecture_room_id,status,created_at) VALUES (?,'FAILED',NOW())")
                .param(room).update();
        long id = jdbc.sql("SELECT id FROM lecture_reports WHERE lecture_room_id=?").param(room).query(Long.class).single();
        for (String reason : new String[]{"LLM_REQUEST_FAILED", "GENERATION_INTERRUPTED", "INVALID_REPORT_EVIDENCE", "REPORT_STORAGE_FAILED"}) {
            jdbc.sql("UPDATE lecture_reports SET error_message=? WHERE id=?").params(reason, id).update();
            mvc.perform(post(path, room).with(csrf()).with(user(owner).roles("HOST")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FAILED"));
        }
        jdbc.sql("UPDATE lecture_reports SET error_message=? WHERE id=?").params(eligibleReason, id).update();
        mvc.perform(post(path, room).with(csrf()).with(user(owner).roles("HOST")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.reportId").value(id))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        assertThat(jdbc.sql("SELECT retry_used AND error_message IS NULL FROM lecture_reports WHERE id=?")
                .param(id).query(Boolean.class).single()).isTrue();
        jdbc.sql("UPDATE lecture_reports SET status='FAILED', error_message=? WHERE id=?").params(eligibleReason, id).update();
        mvc.perform(post(path, room).with(csrf()).with(user(owner).roles("HOST")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reportId").value(id))
                .andExpect(jsonPath("$.data.status").value("FAILED"));
    }
}
