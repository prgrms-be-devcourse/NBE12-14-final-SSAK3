package com.react.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatMessageTest {
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    private long hostUser;
    private long room;
    private long participant;
    // 방 안의 메시지 id. 오래된 순: 청중, 강연자, 청중, 청중, 강연자
    private final List<Long> ids = new ArrayList<>();

    @BeforeEach
    void prepareRoom() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        hostUser = insert("INSERT INTO users(email,password_hash,name,created_at) VALUES (?, 'hash','강연자', NOW())",
                UUID.randomUUID() + "@example.com");
        room = newRoom();
        participant = insert("""
                INSERT INTO lecture_participants(lecture_room_id,nickname,participant_token_hash,joined_at)
                VALUES (?, '청중A', ?, NOW())
                """, room, UUID.randomUUID().toString());
        ids.add(message(room, participant, null, "첫 번째", "2026-11-17 10:00:01"));
        ids.add(message(room, null, hostUser, "두 번째", "2026-11-17 10:00:02"));
        ids.add(message(room, participant, null, "세 번째", "2026-11-17 10:00:03"));
        ids.add(message(room, participant, null, "네 번째", "2026-11-17 10:00:04"));
        ids.add(message(room, null, hostUser, "다섯 번째", "2026-11-17 10:00:05"));
    }

    @Test
    void cursorPagingReturnsOldestFirstWithinEachPage() throws Exception {
        JsonNode first = page("?size=2");
        assertThat(ids(first)).containsExactly(ids.get(3), ids.get(4));
        assertThat(first.get("hasNext").asBoolean()).isTrue();
        assertThat(first.get("nextCursor").asLong()).isEqualTo(ids.get(3));

        JsonNode second = page("?size=2&cursor=" + first.get("nextCursor").asLong());
        assertThat(ids(second)).containsExactly(ids.get(1), ids.get(2));
        assertThat(second.get("hasNext").asBoolean()).isTrue();
        assertThat(second.get("nextCursor").asLong()).isEqualTo(ids.get(1));

        JsonNode last = page("?size=2&cursor=" + second.get("nextCursor").asLong());
        assertThat(ids(last)).containsExactly(ids.get(0));
        assertThat(last.get("hasNext").asBoolean()).isFalse();
        assertThat(last.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void hasNextBoundary() throws Exception {
        JsonNode exact = page("?size=5");
        assertThat(ids(exact)).containsExactlyElementsOf(ids);
        assertThat(exact.get("hasNext").asBoolean()).isFalse();
        assertThat(exact.get("nextCursor").isNull()).isTrue();

        JsonNode oneShort = page("?size=4");
        assertThat(ids(oneShort)).containsExactlyElementsOf(ids.subList(1, 5));
        assertThat(oneShort.get("hasNext").asBoolean()).isTrue();
        assertThat(oneShort.get("nextCursor").asLong()).isEqualTo(ids.get(1));
    }

    @Test
    void defaultSizeAndSenderMapping() throws Exception {
        JsonNode body = page("");
        assertThat(ids(body)).containsExactlyElementsOf(ids);

        JsonNode byParticipant = body.get("messages").get(0);
        assertThat(byParticipant.get("sender").get("type").asText()).isEqualTo("PARTICIPANT");
        assertThat(byParticipant.get("sender").get("id").asLong()).isEqualTo(participant);
        assertThat(byParticipant.get("sender").get("name").asText()).isEqualTo("청중A");
        assertThat(byParticipant.get("content").asText()).isEqualTo("첫 번째");
        // DATETIME 은 +09:00 기준으로 저장하므로 10:00:01(+09:00) 은 01:00:01Z
        assertThat(byParticipant.get("receivedAt").asText()).isEqualTo("2026-11-17T01:00:01Z");

        JsonNode byHost = body.get("messages").get(1);
        assertThat(byHost.get("sender").get("type").asText()).isEqualTo("HOST");
        assertThat(byHost.get("sender").get("id").asLong()).isEqualTo(hostUser);
        assertThat(byHost.get("sender").get("name").asText()).isEqualTo("강연자");
    }

    @Test
    void otherRoomMessagesAreNotMixed() throws Exception {
        long otherRoom = newRoom();
        long otherMessage = message(otherRoom, null, hostUser, "다른 방", "2026-11-17 10:00:09");

        assertThat(ids(page(""))).doesNotContain(otherMessage);
        assertThat(ids(page(otherRoom, ""))).containsExactly(otherMessage);
        // 다른 방의 메시지 id 를 커서로 써도 이 방의 범위를 벗어나지 않는다.
        assertThat(ids(page("?cursor=" + (otherMessage + 1)))).containsExactlyElementsOf(ids);
    }

    @Test
    void emptyRoomReturnsEmptyPage() throws Exception {
        JsonNode body = page(newRoom(), "");
        assertThat(body.get("messages")).isEmpty();
        assertThat(body.get("hasNext").asBoolean()).isFalse();
        assertThat(body.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void invalidInputAndUnknownRoom() throws Exception {
        for (String query : List.of("?size=0", "?size=101", "?size=-1", "?cursor=0", "?size=abc")) {
            mvc.perform(get("/api/v1/rooms/{id}/messages" + query, room))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        mvc.perform(get("/api/v1/rooms/{id}/messages", Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
        // 최대 크기는 허용한다.
        page("?size=100");
    }

    private long newRoom() {
        return insert("""
                INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at)
                VALUES (?, 'test', ?, 'LIVE', NOW())
                """, hostUser, UUID.randomUUID().toString());
    }

    private long message(long roomId, Long participantId, Long hostUserId, String content, String receivedAt) {
        return insert("""
                INSERT INTO chat_messages(lecture_room_id,participant_id,host_user_id,content,received_at)
                VALUES (?, ?, ?, ?, ?)
                """, roomId, participantId, hostUserId, content, receivedAt);
    }

    private long insert(String sql, Object... params) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql(sql).params(params).update(keys);
        return keys.getKey().longValue();
    }

    private JsonNode page(String query) throws Exception {
        return page(room, query);
    }

    private JsonNode page(long roomId, String query) throws Exception {
        String body = mvc.perform(get("/api/v1/rooms/{id}/messages" + query, roomId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private List<Long> ids(JsonNode page) {
        List<Long> result = new ArrayList<>();
        page.get("messages").forEach(message -> result.add(message.get("messageId").asLong()));
        return result;
    }
}
