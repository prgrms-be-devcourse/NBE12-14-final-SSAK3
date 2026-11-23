package com.react.poll;

import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.poll.dto.CreatePollRequest;
import com.react.poll.dto.PollResponseView;
import com.react.poll.dto.PollView;
import com.react.poll.entity.PollType;
import com.react.poll.service.PollService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 청중 투표 응답·변경의 저장 규칙과 거절 사유, 같은 청중의 동시 응답을 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
class PollRespondTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired PollService service;
    private String hostEmail;
    private long room;
    private long participant;
    private PollView poll;

    @BeforeEach
    void prepare() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        hostEmail = UUID.randomUUID() + "@example.com";
        long host = insert("INSERT INTO users(email,password_hash,name,created_at) VALUES (?, 'hash', '강연자', ?)",
                hostEmail, LocalDateTime.now(SEOUL));
        room = newRoom(host);
        participant = newParticipant(room);
        poll = service.create(room, hostEmail, new CreatePollRequest(PollType.UNDERSTANDING, null, null, 60));
    }

    @Test
    void firstResponseIsSavedAndMarkedChanged() {
        PollResponseView view = service.respond(room, poll.pollId(), participant, optionId(0));

        assertThat(view.changed()).isTrue();
        assertThat(view.optionId()).isEqualTo(optionId(0));
        assertThat(view.participantId()).isEqualTo(participant);
        assertThat(view.closesAt()).isEqualTo(poll.closesAt());
        assertThat(responses()).isEqualTo(1);
    }

    @Test
    void resendingBeforeCloseChangesChoiceWithoutAddingRow() {
        service.respond(room, poll.pollId(), participant, optionId(0));
        PollResponseView view = service.respond(room, poll.pollId(), participant, optionId(1));

        assertThat(view.changed()).isTrue();
        assertThat(view.optionId()).isEqualTo(optionId(1));
        assertThat(responses()).isEqualTo(1);
        assertThat(savedOption()).isEqualTo(optionId(1));
    }

    @Test
    void sameChoiceAgainIsNotChangedAndKeepsRespondedAt() {
        PollResponseView first = service.respond(room, poll.pollId(), participant, optionId(0));
        PollResponseView again = service.respond(room, poll.pollId(), participant, optionId(0));

        assertThat(again.changed()).isFalse();
        assertThat(again.respondedAt()).isEqualTo(first.respondedAt());
        assertThat(responses()).isEqualTo(1);
    }

    @Test
    void optionOfAnotherPollIsRejected() {
        long otherRoom = newRoom(hostId());
        long otherParticipant = newParticipant(otherRoom);
        PollView other = service.create(otherRoom, hostEmail, new CreatePollRequest(PollType.UNDERSTANDING, null, null, 60));
        long foreignOption = jdbc.sql("SELECT id FROM poll_options WHERE poll_id = ? LIMIT 1")
                .param(other.pollId()).query(Long.class).single();

        assertCode(ErrorCode.INVALID_POLL_OPTION, () -> service.respond(room, poll.pollId(), participant, foreignOption));
        assertCode(ErrorCode.INVALID_POLL_OPTION, () -> service.respond(room, poll.pollId(), participant, -1L));
        assertThat(otherParticipant).isPositive();
        assertThat(responses()).isZero();
    }

    @Test
    void participantOfAnotherRoomIsForbidden() {
        long stranger = newParticipant(newRoom(hostId()));

        assertCode(ErrorCode.FORBIDDEN, () -> service.respond(room, poll.pollId(), stranger, optionId(0)));
        assertCode(ErrorCode.FORBIDDEN, () -> service.respond(room, poll.pollId(), Long.MAX_VALUE, optionId(0)));
        assertThat(responses()).isZero();
    }

    @Test
    void unknownPollAndRoomAreNotFound() {
        assertCode(ErrorCode.POLL_NOT_FOUND, () -> service.respond(room, Long.MAX_VALUE, participant, optionId(0)));
        // 다른 강연방 경로로는 이 방의 투표를 찾지 못한다.
        long otherRoom = newRoom(hostId());
        assertCode(ErrorCode.FORBIDDEN, () -> service.respond(otherRoom, poll.pollId(), participant, optionId(0)));
        assertCode(ErrorCode.ROOM_NOT_FOUND, () -> service.respond(Long.MAX_VALUE, poll.pollId(), participant, optionId(0)));
    }

    @Test
    void closedPollRejectsNewAndChangedResponsesAndKeepsSavedOne() {
        service.respond(room, poll.pollId(), participant, optionId(0));
        service.close(room, poll.pollId(), hostEmail);

        assertCode(ErrorCode.POLL_CLOSED, () -> service.respond(room, poll.pollId(), participant, optionId(1)));
        assertThat(savedOption()).isEqualTo(optionId(0));
    }

    @Test
    void pollPastClosesAtIsClosedEvenIfStatusIsStillOpen() {
        jdbc.sql("UPDATE polls SET closes_at = ? WHERE id = ?")
                .params(LocalDateTime.now(SEOUL).minusSeconds(5), poll.pollId()).update();

        assertCode(ErrorCode.POLL_CLOSED, () -> service.respond(room, poll.pollId(), participant, optionId(0)));
        assertThat(responses()).isZero();
    }

    @Test
    void endedRoomRejectsResponses() {
        jdbc.sql("UPDATE lecture_rooms SET status = 'ENDED' WHERE id = ?").param(room).update();

        assertCode(ErrorCode.ROOM_ENDED, () -> service.respond(room, poll.pollId(), participant, optionId(0)));
        assertThat(responses()).isZero();
    }

    @Test
    void concurrentResponsesFromSameParticipantLeaveOneRowMatchingLastWrite() throws Exception {
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<PollResponseView>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            long option = optionId(i % 3);
            results.add(CompletableFuture.supplyAsync(() -> {
                ready.countDown();
                await(go);
                return service.respond(room, poll.pollId(), participant, option);
            }, executor));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        for (var result : results) {
            result.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // 중복 행도, UNIQUE 위반으로 인한 실패도 없고, 저장된 선택은 요청한 선택지 중 하나다.
        assertThat(responses()).isEqualTo(1);
        assertThat(List.of(optionId(0), optionId(1), optionId(2))).contains(savedOption());
    }

    private static void assertCode(ErrorCode expected, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long optionId(int index) {
        return poll.options().get(index).optionId();
    }

    private long responses() {
        return jdbc.sql("SELECT COUNT(*) FROM poll_responses WHERE poll_id = ?").param(poll.pollId()).query(Long.class).single();
    }

    private long savedOption() {
        return jdbc.sql("SELECT poll_option_id FROM poll_responses WHERE poll_id = ? AND participant_id = ?")
                .params(poll.pollId(), participant).query(Long.class).single();
    }

    private long hostId() {
        return jdbc.sql("SELECT id FROM users WHERE email = ?").param(hostEmail).query(Long.class).single();
    }

    private long newRoom(long host) {
        return insert("""
                INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at)
                VALUES (?, 'test', ?, 'LIVE', ?)
                """, host, UUID.randomUUID().toString(), LocalDateTime.now(SEOUL));
    }

    private long newParticipant(long roomId) {
        return insert("""
                INSERT INTO lecture_participants(lecture_room_id,nickname,participant_token_hash,joined_at)
                VALUES (?, '청중', ?, ?)
                """, roomId, UUID.randomUUID().toString(), LocalDateTime.now(SEOUL));
    }

    private long insert(String sql, Object... params) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql(sql).params(params).update(keys);
        return keys.getKey().longValue();
    }
}
