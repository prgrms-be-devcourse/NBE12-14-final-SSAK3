package com.react.stt;

import com.react.stt.service.SttSessionService;
import com.react.stt.service.SttSessionService.StartResult;
import com.react.stt.service.SttSessionService.StopReason;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SttSessionTest {
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired MockMvc mvc;
    @Autowired SttSessionService service;
    private final String email = UUID.randomUUID() + "@example.com";
    private long room;

    @BeforeEach
    void prepareRoom() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc.sql("INSERT INTO users(email,password_hash,name,created_at) VALUES (?, 'hash','host', NOW())")
                .param(email).update();
        jdbc.sql("""
                INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at)
                SELECT id,'test',?,'LIVE',NOW() FROM users WHERE email=?
                """).params(email, email).update();
        room = jdbc.sql("SELECT id FROM lecture_rooms WHERE join_code=?").param(email).query(Long.class).single();
    }

    private MockHttpServletRequestBuilder start(UUID key) {
        return post("/api/v1/rooms/{id}/stt/sessions", room).contentType("application/json")
                .content("{\"startKey\":\"" + key + "\"}");
    }

    @Test
    void authenticatedLiveOwnerOnly() throws Exception {
        var key = UUID.randomUUID();
        mvc.perform(start(key)).andExpect(status().isUnauthorized());
        mvc.perform(start(key).with(user("other@example.com"))).andExpect(status().isForbidden());
        mvc.perform(start(key).with(user(email))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.canRecord").value(true));
        mvc.perform(start(key).with(user(email))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").value(room))
                .andExpect(jsonPath("$.data.sttSessionId").isNumber());
        long id = service.start(room, email, key).session().sttSessionId();
        mvc.perform(post("/api/v1/rooms/{room}/stt/sessions/{id}/stop", room, id)
                        .with(user(email)).contentType("application/json")
                        .content("{\"reason\":\"MANUAL_STOP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("STOPPED"))
                .andExpect(jsonPath("$.data.canRecord").value(false));
        jdbc.sql("UPDATE lecture_rooms SET status='ENDED' WHERE id=?").param(room).update();
        mvc.perform(start(UUID.randomUUID()).with(user(email))).andExpect(status().isConflict());
    }

    @Test
    void simultaneousStartsAndLateRequests() throws Exception {
        var gate = new CyclicBarrier(2);
        var firstKey = UUID.randomUUID();
        var secondKey = UUID.randomUUID();
        StartResult first;
        StartResult second;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(contender(gate, firstKey));
            var b = pool.submit(contender(gate, secondKey));
            first = a.get(10, TimeUnit.SECONDS);
            second = b.get(10, TimeUnit.SECONDS);
        }
        long id = first.session().sttSessionId();
        assertThat(second.session().sttSessionId()).isEqualTo(id);
        assertThat(first.created()).isNotEqualTo(second.created());
        assertThat(first.session().canRecord()).isEqualTo(first.created());
        assertThat(second.session().canRecord()).isEqualTo(second.created());
        var stopped = service.stop(room, id, email, StopReason.MANUAL_STOP);
        assertThat(stopped.endedAt()).isNotNull();
        var replacement = service.start(room, email, UUID.randomUUID());
        assertThat(replacement.created()).isTrue();
        assertThat(service.stop(room, id, email, StopReason.ERROR)).isEqualTo(stopped);
        var delayed = service.start(room, email, first.created() ? firstKey : secondKey);
        assertThat(delayed.session()).isEqualTo(stopped);
    }

    private Callable<StartResult> contender(CyclicBarrier gate, UUID key) {
        return () -> {
            gate.await(5, TimeUnit.SECONDS);
            return service.start(room, email, key);
        };
    }
}
