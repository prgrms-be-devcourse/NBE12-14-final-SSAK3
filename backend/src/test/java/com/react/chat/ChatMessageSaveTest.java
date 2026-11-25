package com.react.chat;

import com.react.chat.dto.ChatMessagePageView;
import com.react.chat.dto.ChatMessageView;
import com.react.chat.dto.SenderType;
import com.react.chat.service.ChatMessageService;
import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.global.websocket.StompActor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class ChatMessageSaveTest {
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired ChatMessageService service;
    @Autowired PlatformTransactionManager transactionManager;
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
    void 종료_중에_들어온_채팅은_종료가_커밋될_때까지_기다렸다가_거절된다() throws Exception {
        var send = new TransactionTemplate(transactionManager).execute(transaction -> {
            jdbc.sql("UPDATE lecture_rooms SET status = 'ENDED' WHERE id = ?").param(room).update();
            var pending = CompletableFuture.supplyAsync(() -> service.saveFromParticipant(room, participant, "질문"));
            assertThatThrownBy(() -> pending.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
            return pending;
        });
        assertThatThrownBy(() -> send.get(5, TimeUnit.SECONDS)).rootCause()
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ROOM_ENDED));
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
    void 청중이_같은_전송_식별자로_다시_보내면_기존_메시지를_돌려주고_새로_저장하지_않는다() {
        String id = UUID.randomUUID().toString();

        ChatMessageView first = service.saveFromParticipant(room, participant, "질문입니다", id);
        ChatMessageView again = service.saveFromParticipant(room, participant, "질문입니다", id);

        assertThat(again.messageId()).isEqualTo(first.messageId());
        assertThat(again.sender()).isEqualTo(first.sender());
        assertThat(again.receivedAt()).isEqualTo(first.receivedAt());
        assertThat(messageCount(room)).isEqualTo(1);
    }

    @Test
    void 강연자가_같은_전송_식별자로_다시_보내면_기존_메시지를_돌려주고_새로_저장하지_않는다() {
        String id = UUID.randomUUID().toString();

        ChatMessageView first = service.saveFromHost(room, hostUser, "설명입니다", id);
        ChatMessageView again = service.saveFromHost(room, hostUser, "설명입니다", id);

        assertThat(again.messageId()).isEqualTo(first.messageId());
        assertThat(messageCount(room)).isEqualTo(1);
    }

    @Test
    void 전송_식별자가_없으면_중복_검사를_하지_않는다() {
        ChatMessageView first = service.saveFromParticipant(room, participant, "같은 문구", null);
        ChatMessageView second = service.saveFromParticipant(room, participant, "같은 문구", null);
        service.saveFromHost(room, hostUser, "같은 문구", null);
        service.saveFromHost(room, hostUser, "같은 문구", null);

        assertThat(second.messageId()).isNotEqualTo(first.messageId());
        assertThat(messageCount(room)).isEqualTo(4);
    }

    @Test
    void 같은_전송_식별자라도_작성자가_다르면_각각_저장한다() {
        String id = UUID.randomUUID().toString();
        long otherParticipant = newParticipant(room, "청중B");

        ChatMessageView a = service.saveFromParticipant(room, participant, "A", id);
        ChatMessageView b = service.saveFromParticipant(room, otherParticipant, "B", id);
        ChatMessageView host = service.saveFromHost(room, hostUser, "H", id);

        assertThat(java.util.Set.of(a.messageId(), b.messageId(), host.messageId())).hasSize(3);
        assertThat(messageCount(room)).isEqualTo(3);
    }

    @Test
    void 같은_강연자라도_강연방이_다르면_같은_전송_식별자를_각각_저장한다() {
        String id = UUID.randomUUID().toString();
        long otherRoom = newRoom(hostUser, "LIVE");

        ChatMessageView here = service.saveFromHost(room, hostUser, "여기", id);
        ChatMessageView there = service.saveFromHost(otherRoom, hostUser, "저기", id);

        assertThat(there.messageId()).isNotEqualTo(here.messageId());
        assertThat(messageCount(room)).isEqualTo(1);
        assertThat(messageCount(otherRoom)).isEqualTo(1);
    }

    @Test
    void 종료된_강연방에서는_이미_저장된_전송_식별자의_재전송도_거절한다() {
        String id = UUID.randomUUID().toString();
        service.saveFromParticipant(room, participant, "질문", id);
        service.saveFromHost(room, hostUser, "답변", id);
        jdbc.sql("UPDATE lecture_rooms SET status = 'ENDED' WHERE id = ?").param(room).update();

        assertRejected(ErrorCode.ROOM_ENDED, () -> service.saveFromParticipant(room, participant, "질문", id));
        assertRejected(ErrorCode.ROOM_ENDED, () -> service.saveFromHost(room, hostUser, "답변", id));
        assertThat(messageCount(room)).isEqualTo(2);
    }

    @Test
    void 동시에_들어온_같은_전송_식별자는_DB_유니크_제약이_막는다() {
        String id = UUID.randomUUID().toString();
        String insertParticipant = "INSERT INTO chat_messages(lecture_room_id,participant_id,content,received_at,client_message_id)"
                + " VALUES (?, ?, 'x', NOW(), ?)";
        String insertHost = "INSERT INTO chat_messages(lecture_room_id,host_user_id,content,received_at,client_message_id)"
                + " VALUES (?, ?, 'x', NOW(), ?)";

        jdbc.sql(insertParticipant).params(room, participant, id).update();
        jdbc.sql(insertHost).params(room, hostUser, id).update();

        assertThatThrownBy(() -> jdbc.sql(insertParticipant).params(room, participant, id).update())
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.sql(insertHost).params(room, hostUser, id).update())
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(messageCount(room)).isEqualTo(2);
    }

    @Test
    void savedMessagesAreReadableThroughHistory() {
        ChatMessageView first = service.saveFromParticipant(room, participant, "질문입니다");
        ChatMessageView second = service.saveFromHost(room, hostUser, "답변입니다");

        ChatMessagePageView page = service.getMessages(room, StompActor.host(hostUser), null, 30);

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
                INSERT INTO lecture_rooms(host_user_id,title,join_code,status,started_at,created_at)
                VALUES (?, 'test', ?, ?, '2026-01-01 00:00:00', NOW())
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
