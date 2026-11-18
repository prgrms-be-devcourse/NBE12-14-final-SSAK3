package com.react.feed;

import com.react.feed.dto.MyUnderstandingView;
import com.react.feed.dto.UnderstandingChangeView;
import com.react.feed.dto.UnderstandingStatus;
import com.react.feed.service.UnderstandingService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static com.react.feed.dto.UnderstandingStatus.CONFUSED;
import static com.react.feed.dto.UnderstandingStatus.NOT_UNDERSTOOD;
import static com.react.feed.dto.UnderstandingStatus.UNDERSTOOD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class UnderstandingServiceTest {
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired UnderstandingService service;
    private long room;
    private long participant;

    @BeforeEach
    void prepareRoom() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        room = newRoom(newUser("강연자"), "LIVE");
        participant = newParticipant(room, "청중A");
    }

    @Test
    void unansweredIsNullNotNotUnderstood() {
        MyUnderstandingView view = service.getMine(room, participant);

        assertThat(view.participantId()).isEqualTo(participant);
        assertThat(view.status()).isNull();
        assertThat(view.updatedAt()).isNull();
    }

    @Test
    void firstChoiceCreatesCurrentAndHistory() {
        UnderstandingChangeView result = service.change(room, participant, CONFUSED);

        assertThat(result.changed()).isTrue();
        assertThat(result.previousStatus()).isNull();
        assertThat(result.currentStatus()).isEqualTo(CONFUSED);
        assertThat(result.roomId()).isEqualTo(room);
        assertThat(currentCount()).isEqualTo(1);
        assertThat(historyCount()).isEqualTo(1);

        MyUnderstandingView mine = service.getMine(room, participant);
        assertThat(mine.status()).isEqualTo(CONFUSED);
        assertThat(mine.updatedAt()).isEqualTo(result.updatedAt());
    }

    @Test
    void changeUpdatesCurrentAndAppendsHistory() {
        service.change(room, participant, UNDERSTOOD);
        UnderstandingChangeView result = service.change(room, participant, CONFUSED);

        assertThat(result.changed()).isTrue();
        assertThat(result.previousStatus()).isEqualTo(UNDERSTOOD);
        assertThat(result.currentStatus()).isEqualTo(CONFUSED);
        assertThat(currentCount()).isEqualTo(1);
        assertThat(historyCount()).isEqualTo(2);
        assertThat(service.getMine(room, participant).status()).isEqualTo(CONFUSED);
    }

    @Test
    void sameStatusIsNoOp() {
        UnderstandingChangeView first = service.change(room, participant, CONFUSED);
        UnderstandingChangeView again = service.change(room, participant, CONFUSED);

        assertThat(again.changed()).isFalse();
        assertThat(again.previousStatus()).isEqualTo(CONFUSED);
        assertThat(again.updatedAt()).isEqualTo(first.updatedAt());
        assertThat(historyCount()).isEqualTo(1);
    }

    @Test
    void rejectsNonLiveRoomsAndKeepsRecordsUntouched() {
        long ready = newRoom(newUser("대기 강연자"), "READY");
        long readyParticipant = newParticipant(ready, "대기청중");
        assertRejected(ErrorCode.ROOM_NOT_LIVE, () -> service.change(ready, readyParticipant, CONFUSED));

        service.change(room, participant, UNDERSTOOD);
        jdbc.sql("UPDATE lecture_rooms SET status = 'ENDED' WHERE id = ?").param(room).update();
        assertRejected(ErrorCode.ROOM_ENDED, () -> service.change(room, participant, CONFUSED));

        assertThat(service.getMine(room, participant).status()).isEqualTo(UNDERSTOOD);
        assertThat(historyCount()).isEqualTo(1);
    }

    @Test
    void rejectsUnknownRoomForeignParticipantAndNullStatus() {
        long otherRoom = newRoom(newUser("다른 강연자"), "LIVE");
        long otherParticipant = newParticipant(otherRoom, "다른방 청중");

        assertRejected(ErrorCode.ROOM_NOT_FOUND, () -> service.change(Long.MAX_VALUE, participant, CONFUSED));
        assertRejected(ErrorCode.ROOM_NOT_FOUND, () -> service.getMine(Long.MAX_VALUE, participant));
        assertRejected(ErrorCode.FORBIDDEN, () -> service.change(room, otherParticipant, CONFUSED));
        assertRejected(ErrorCode.FORBIDDEN, () -> service.change(room, Long.MAX_VALUE, CONFUSED));
        assertRejected(ErrorCode.FORBIDDEN, () -> service.getMine(room, otherParticipant));
        assertRejected(ErrorCode.INVALID_INPUT, () -> service.change(room, participant, null));
        assertThat(historyCount()).isZero();
    }

    @Test
    void concurrentRequestsKeepSingleCurrentRow() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        try {
            UnderstandingStatus[] choices = {UNDERSTOOD, CONFUSED, NOT_UNDERSTOOD};
            List<Callable<UnderstandingChangeView>> calls = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                UnderstandingStatus choice = choices[i % 3];
                calls.add(() -> service.change(room, participant, choice));
            }
            for (var future : pool.invokeAll(calls)) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(currentCount()).isEqualTo(1);
        // 이력은 실제 변경이 일어난 횟수만큼만 쌓이고, 마지막 이력이 최신 상태와 같다.
        assertThat(historyCount()).isBetween(1L, 12L);
        String last = jdbc.sql("SELECT new_status FROM understanding_histories WHERE participant_id = ? ORDER BY id DESC LIMIT 1")
                .param(participant).query(String.class).single();
        assertThat(service.getMine(room, participant).status().name()).isEqualTo(last);
    }

    private void assertRejected(ErrorCode expected, Supplier<?> call) {
        assertThatThrownBy(call::get).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private long currentCount() {
        return jdbc.sql("SELECT COUNT(*) FROM participant_understandings WHERE participant_id = ?")
                .param(participant).query(Long.class).single();
    }

    private long historyCount() {
        return jdbc.sql("SELECT COUNT(*) FROM understanding_histories WHERE participant_id = ?")
                .param(participant).query(Long.class).single();
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
