package com.react.chat;

import com.react.chat.dto.ChatMessagePageView;
import com.react.chat.dto.ChatMessageView;
import com.react.chat.dto.SenderType;
import com.react.chat.service.ChatMessageService;
import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class ChatMessageSaveTest {
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired ChatMessageService service;
    private long hostUser;
    private long room;
    private long participant;

    @BeforeEach
    void prepareRoom() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        hostUser = newUser("강연자");
        room = newRoom(hostUser, "LIVE");
        participant = newParticipant(room, "청중A");
    }

    @Test
    void participantMessageIsSavedWithParticipantOnly() {
        ChatMessageView saved = service.saveFromParticipant(room, participant, "  안녕하세요  ");

        assertThat(saved.sender().type()).isEqualTo(SenderType.PARTICIPANT);
        assertThat(saved.sender().id()).isEqualTo(participant);
        assertThat(saved.sender().name()).isEqualTo("청중A");
        assertThat(saved.content()).isEqualTo("안녕하세요");

        var row = jdbc.sql("SELECT lecture_room_id, participant_id, host_user_id, content FROM chat_messages WHERE id = ?")
                .param(saved.messageId()).query().singleRow();
        assertThat(((Number) row.get("lecture_room_id")).longValue()).isEqualTo(room);
        assertThat(((Number) row.get("participant_id")).longValue()).isEqualTo(participant);
        assertThat(row.get("host_user_id")).isNull();
        assertThat(row.get("content")).isEqualTo("안녕하세요");
    }

    @Test
    void hostMessageIsSavedWithHostOnly() {
        ChatMessageView saved = service.saveFromHost(room, hostUser, "예시를 들어 설명할게요.");

        assertThat(saved.sender().type()).isEqualTo(SenderType.HOST);
        assertThat(saved.sender().id()).isEqualTo(hostUser);
        assertThat(saved.sender().name()).isEqualTo("강연자");

        var row = jdbc.sql("SELECT participant_id, host_user_id FROM chat_messages WHERE id = ?")
                .param(saved.messageId()).query().singleRow();
        assertThat(row.get("participant_id")).isNull();
        assertThat(((Number) row.get("host_user_id")).longValue()).isEqualTo(hostUser);
    }

    @Test
    void blankOrTooLongContentIsRejected() {
        for (String content : new String[]{null, "", "   ", "\n\t "}) {
            assertRejected(ErrorCode.INVALID_INPUT, () -> service.saveFromParticipant(room, participant, content));
        }
        assertRejected(ErrorCode.INVALID_INPUT, () -> service.saveFromHost(room, hostUser, " "));
        assertRejected(ErrorCode.INVALID_INPUT, () -> service.saveFromParticipant(room, participant, "가".repeat(1001)));
        // 이모지는 UTF-16 으로 2칸이지만 한 글자로 센다.
        assertRejected(ErrorCode.INVALID_INPUT, () -> service.saveFromParticipant(room, participant, "😀".repeat(1001)));
        assertThat(messageCount(room)).isZero();

        service.saveFromParticipant(room, participant, "가".repeat(1000));
        service.saveFromParticipant(room, participant, "😀".repeat(1000));
        assertThat(messageCount(room)).isEqualTo(2);
    }

    @Test
    void onlyLiveRoomAcceptsMessages() {
        long ready = newRoom(hostUser, "READY");
        long readyParticipant = newParticipant(ready, "대기청중");
        assertRejected(ErrorCode.ROOM_NOT_LIVE, () -> service.saveFromParticipant(ready, readyParticipant, "안녕"));
        assertRejected(ErrorCode.ROOM_NOT_LIVE, () -> service.saveFromHost(ready, hostUser, "안녕"));

        jdbc.sql("UPDATE lecture_rooms SET status = 'ENDED' WHERE id = ?").param(room).update();
        assertRejected(ErrorCode.ROOM_ENDED, () -> service.saveFromParticipant(room, participant, "안녕"));
        assertRejected(ErrorCode.ROOM_ENDED, () -> service.saveFromHost(room, hostUser, "안녕"));

        assertRejected(ErrorCode.ROOM_NOT_FOUND, () -> service.saveFromHost(Long.MAX_VALUE, hostUser, "안녕"));
        assertRejected(ErrorCode.ROOM_NOT_FOUND, () -> service.saveFromParticipant(Long.MAX_VALUE, participant, "안녕"));

        assertThat(messageCount(ready)).isZero();
        assertThat(messageCount(room)).isZero();
    }

    @Test
    void senderMustBelongToTheRoom() {
        long otherRoom = newRoom(newUser("다른 강연자"), "LIVE");
        long otherParticipant = newParticipant(otherRoom, "다른방 청중");
        long otherHost = jdbc.sql("SELECT host_user_id FROM lecture_rooms WHERE id = ?")
                .param(otherRoom).query(Long.class).single();

        // 다른 방의 참여자·강연자, 존재하지 않는 사용자는 이 방에 쓸 수 없다.
        assertRejected(ErrorCode.FORBIDDEN, () -> service.saveFromParticipant(room, otherParticipant, "침입"));
        assertRejected(ErrorCode.FORBIDDEN, () -> service.saveFromParticipant(room, Long.MAX_VALUE, "침입"));
        assertRejected(ErrorCode.FORBIDDEN, () -> service.saveFromHost(room, otherHost, "침입"));
        assertRejected(ErrorCode.FORBIDDEN, () -> service.saveFromHost(room, Long.MAX_VALUE, "침입"));
        assertThat(messageCount(room)).isZero();
        assertThat(messageCount(otherRoom)).isZero();

        // 권한 없는 사람에게는 방 상태도 알려 주지 않는다 (종료된 방이어도 FORBIDDEN).
        jdbc.sql("UPDATE lecture_rooms SET status = 'ENDED' WHERE id = ?").param(room).update();
        assertRejected(ErrorCode.FORBIDDEN, () -> service.saveFromParticipant(room, otherParticipant, "침입"));
    }

    @Test
    void savedMessagesAreReadableThroughHistory() {
        ChatMessageView first = service.saveFromParticipant(room, participant, "질문입니다");
        ChatMessageView second = service.saveFromHost(room, hostUser, "답변입니다");

        ChatMessagePageView page = service.getMessages(room, null, 30);

        assertThat(page.messages()).extracting(ChatMessageView::messageId)
                .containsExactly(first.messageId(), second.messageId());
        assertThat(page.messages().get(0).sender().type()).isEqualTo(SenderType.PARTICIPANT);
        assertThat(page.messages().get(1).sender().type()).isEqualTo(SenderType.HOST);
        assertThat(page.messages().get(0).receivedAt()).isEqualTo(first.receivedAt());
    }

    private void assertRejected(ErrorCode expected, Supplier<?> call) {
        assertThatThrownBy(call::get).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private long messageCount(long roomId) {
        return jdbc.sql("SELECT COUNT(*) FROM chat_messages WHERE lecture_room_id = ?")
                .param(roomId).query(Long.class).single();
    }

    private long newUser(String name) {
        return insert("INSERT INTO users(email,password_hash,name,created_at) VALUES (?, 'hash', ?, NOW())",
                UUID.randomUUID() + "@example.com", name);
    }

    private long newRoom(long hostUserId, String status) {
        return insert("""
                INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at)
                VALUES (?, 'test', ?, ?, NOW())
                """, hostUserId, UUID.randomUUID().toString(), status);
    }

    private long newParticipant(long roomId, String nickname) {
        return insert("""
                INSERT INTO lecture_participants(lecture_room_id,nickname,participant_token_hash,joined_at)
                VALUES (?, ?, ?, NOW())
                """, roomId, nickname, UUID.randomUUID().toString());
    }

    private long insert(String sql, Object... params) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql(sql).params(params).update(keys);
        return keys.getKey().longValue();
    }
}
