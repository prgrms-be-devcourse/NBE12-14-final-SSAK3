package com.react.poll;

import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.poll.dto.CreatePollRequest;
import com.react.poll.dto.PollOptionResultView;
import com.react.poll.dto.PollResultView;
import com.react.poll.dto.PollView;
import com.react.poll.dto.UnderstandingRoundView;
import com.react.poll.entity.PollType;
import com.react.poll.entity.UnderstandingStatus;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 이해도·투표 집계 정합성(SCRUM-174): 같은 선택 반복, 빠른 연속 변경, 다수 동시 응답, 마감·종료 직전 응답,
 * 종료 후 변경 차단, 결과 조회와 라운드 이력의 일치를 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PollConsistencyTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired PollService service;
    private String hostEmail;
    private long host;
    private long room;
    private PollView poll;

    @BeforeEach
    void prepare() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        hostEmail = UUID.randomUUID() + "@example.com";
        host = insert("INSERT INTO users(email,password_hash,name,created_at) VALUES (?, 'hash', '강연자', ?)",
                hostEmail, LocalDateTime.now(SEOUL));
        room = newRoom();
        poll = service.create(room, hostEmail, new CreatePollRequest(PollType.UNDERSTANDING, null, null, 60));
    }

    @Test
    void 같은_선택을_반복해도_응답_수와_응답_시각이_변하지_않는다() {
        long participant = newParticipant(room);
        var first = service.respond(room, poll.pollId(), participant, optionId(poll, 0));

        for (int i = 0; i < 20; i++) {
            var again = service.respond(room, poll.pollId(), participant, optionId(poll, 0));
            assertThat(again.changed()).isFalse();
            assertThat(again.respondedAt()).isEqualTo(first.respondedAt());
        }

        assertCounts(service.results(room, poll.pollId(), hostEmail), 1, 0, 0);
        assertThat(rows(poll)).isEqualTo(1);
    }

    @Test
    void 빠른_연속_변경은_마지막_선택에만_한_번_센다() {
        long participant = newParticipant(room);
        long other = newParticipant(room);
        service.respond(room, poll.pollId(), other, optionId(poll, 1));

        // 이해됨 → 헷갈림 → 모르겠음 → 이해됨 → 이해됨 → 헷갈림 순으로 연달아 바꾼다.
        int[] sequence = {0, 1, 2, 0, 0, 1};
        for (int choice : sequence) {
            service.respond(room, poll.pollId(), participant, optionId(poll, choice));
            PollResultView snapshot = service.results(room, poll.pollId(), hostEmail);
            assertConsistent(snapshot);
            assertThat(snapshot.respondedParticipants()).isEqualTo(2);
        }

        assertCounts(service.results(room, poll.pollId(), hostEmail), 0, 2, 0);
        assertThat(rows(poll)).isEqualTo(2);
    }

    @Test
    void 다수_청중이_동시에_응답해도_한_명도_빠지거나_겹치지_않는다() throws Exception {
        int audience = 60;
        List<Long> participants = newParticipants(room, audience + 5);
        List<Long> responders = participants.subList(0, audience);

        runConcurrently(responders.size(), index ->
                service.respond(room, poll.pollId(), responders.get(index), optionId(poll, index % 3)));

        PollResultView result = service.results(room, poll.pollId(), hostEmail);
        assertConsistent(result);
        assertCounts(result, 20, 20, 20);
        assertThat(result.totalParticipants()).isEqualTo(audience + 5);
        assertThat(result.respondedParticipants()).isEqualTo(audience);
        assertThat(result.unrespondedParticipants()).isEqualTo(5);
        assertThat(rows(poll)).isEqualTo(audience);
    }

    @Test
    void 백_명이_한꺼번에_응답하고_곧바로_모두_선택을_바꿔도_결과가_맞고_커넥션이_막히지_않는다() throws Exception {
        List<Long> participants = newParticipants(room, 100);

        // 이해됨 40, 헷갈림 35, 모르겠음 25 로 동시에 응답한다.
        runConcurrently(participants.size(), index ->
                service.respond(room, poll.pollId(), participants.get(index), optionId(poll, group(index))));
        PollResultView first = service.results(room, poll.pollId(), hostEmail);
        assertConsistent(first);
        assertCounts(first, 40, 35, 25);
        assertThat(first.totalParticipants()).isEqualTo(100);
        assertThat(first.respondedParticipants()).isEqualTo(100);
        assertThat(first.unrespondedParticipants()).isZero();

        // 전원이 동시에 한 칸씩 옮긴다. 응답자 수는 그대로이고 행도 늘지 않는다.
        runConcurrently(participants.size(), index ->
                service.respond(room, poll.pollId(), participants.get(index), optionId(poll, (group(index) + 1) % 3)));
        PollResultView second = service.results(room, poll.pollId(), hostEmail);
        assertConsistent(second);
        assertCounts(second, 25, 40, 35);
        assertThat(second.respondedParticipants()).isEqualTo(100);
        assertThat(rows(poll)).isEqualTo(100);
    }

    @Test
    void 응답과_변경이_몰리는_동안_읽은_결과도_항상_앞뒤가_맞는다() throws Exception {
        List<Long> participants = newParticipants(room, 30);
        AtomicBoolean writing = new AtomicBoolean(true);
        List<PollResultView> snapshots = new ArrayList<>();

        CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
            while (writing.get()) {
                snapshots.add(service.results(room, poll.pollId(), hostEmail));
            }
            snapshots.add(service.results(room, poll.pollId(), hostEmail));
        });
        // 모두 한 번 응답하고, 곧바로 다른 선택으로 한 번 더 바꾼다.
        runConcurrently(participants.size(), index -> {
            service.respond(room, poll.pollId(), participants.get(index), optionId(poll, index % 3));
            service.respond(room, poll.pollId(), participants.get(index), optionId(poll, (index + 1) % 3));
        });
        writing.set(false);
        reader.get(10, TimeUnit.SECONDS);

        assertThat(snapshots).isNotEmpty().allSatisfy(PollConsistencyTest::assertConsistent);
        PollResultView last = snapshots.getLast();
        assertThat(last.respondedParticipants()).isEqualTo(30);
        assertCounts(last, 10, 10, 10);
    }

    @Test
    void 마감과_겹친_응답은_저장되었거나_거절되었거나_둘_중_하나이고_마감_뒤에는_변하지_않는다() throws Exception {
        List<Long> participants = newParticipants(room, 40);
        List<Boolean> accepted = new ArrayList<>();

        runConcurrently(participants.size() + 1, index -> {
            if (index == 0) {
                service.close(room, poll.pollId(), hostEmail);
                return;
            }
            try {
                service.respond(room, poll.pollId(), participants.get(index - 1), optionId(poll, index % 3));
                synchronized (accepted) {
                    accepted.add(true);
                }
            } catch (BusinessException e) {
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.POLL_CLOSED);
            }
        });

        // 성공으로 돌려준 응답 수와 저장된 행 수, 집계의 응답자 수가 모두 같다.
        PollResultView closed = service.results(room, poll.pollId(), hostEmail);
        assertConsistent(closed);
        assertThat(closed.respondedParticipants()).isEqualTo(accepted.size());
        assertThat(rows(poll)).isEqualTo(accepted.size());

        // 마감 뒤에는 새 응답도 선택 변경도 거절되고 집계가 그대로다.
        long late = newParticipant(room);
        assertCode(ErrorCode.POLL_CLOSED, () -> service.respond(room, poll.pollId(), late, optionId(poll, 0)));
        PollResultView after = service.results(room, poll.pollId(), hostEmail);
        assertThat(after.options()).extracting(PollOptionResultView::count)
                .isEqualTo(closed.options().stream().map(PollOptionResultView::count).toList());
        assertThat(after.respondedParticipants()).isEqualTo(closed.respondedParticipants());
    }

    @Test
    void 강연_종료와_겹친_응답도_저장된_만큼만_집계되고_종료_뒤_변경은_막힌다() throws Exception {
        List<Long> participants = newParticipants(room, 40);
        List<Boolean> accepted = new ArrayList<>();

        runConcurrently(participants.size() + 1, index -> {
            if (index == 0) {
                jdbc.sql("UPDATE lecture_rooms SET status = 'ENDED' WHERE id = ?").param(room).update();
                service.closeOpenPollsOnRoomEnded(room);
                return;
            }
            try {
                service.respond(room, poll.pollId(), participants.get(index - 1), optionId(poll, index % 3));
                synchronized (accepted) {
                    accepted.add(true);
                }
            } catch (BusinessException e) {
                assertThat(e.getErrorCode()).isIn(ErrorCode.ROOM_ENDED, ErrorCode.POLL_CLOSED);
            }
        });

        PollResultView ended = service.results(room, poll.pollId(), hostEmail);
        assertConsistent(ended);
        assertThat(ended.respondedParticipants()).isEqualTo(accepted.size());
        assertThat(rows(poll)).isEqualTo(accepted.size());

        // 이미 응답한 청중이 선택을 바꾸려 해도, 새 청중이 응답하려 해도 거절된다.
        long late = newParticipant(room);
        assertCode(ErrorCode.ROOM_ENDED, () -> service.respond(room, poll.pollId(), late, optionId(poll, 0)));
        if (!accepted.isEmpty()) {
            long answered = jdbc.sql("SELECT participant_id FROM poll_responses WHERE poll_id = ? LIMIT 1")
                    .param(poll.pollId()).query(Long.class).single();
            assertCode(ErrorCode.ROOM_ENDED, () -> service.respond(room, poll.pollId(), answered, optionId(poll, 2)));
        }
        assertThat(service.results(room, poll.pollId(), hostEmail).respondedParticipants()).isEqualTo(accepted.size());
    }

    @Test
    void 라운드_이력은_각_라운드의_결과_조회와_같은_숫자이고_다음_라운드가_앞_라운드를_바꾸지_않는다() {
        List<Long> participants = newParticipants(room, 10);
        // 1라운드: 이해됨 5, 헷갈림 3, 모르겠음 1 (한 명은 응답하지 않음). 중간에 한 명이 선택을 바꾼다.
        for (int i = 0; i < 9; i++) {
            int choice = i < 5 ? 0 : i < 8 ? 1 : 2;
            service.respond(room, poll.pollId(), participants.get(i), optionId(poll, i == 4 ? 2 : choice));
        }
        service.respond(room, poll.pollId(), participants.get(4), optionId(poll, 0));
        PollView first = poll;
        PollResultView firstResult = service.results(room, first.pollId(), hostEmail);
        service.close(room, first.pollId(), hostEmail);

        // 2라운드: 같은 청중이 전혀 다르게 답하고, 새 청중도 한 명 들어와 응답한다.
        PollView second = service.create(room, hostEmail, new CreatePollRequest(PollType.UNDERSTANDING, null, null, 60));
        long newcomer = newParticipant(room);
        participants.forEach(id -> service.respond(room, second.pollId(), id, optionId(second, 2)));
        service.respond(room, second.pollId(), newcomer, optionId(second, 0));
        PollResultView secondResult = service.results(room, second.pollId(), hostEmail);
        service.close(room, second.pollId(), hostEmail);

        var rounds = service.understandingRounds(room, hostEmail, null, null).rounds();
        assertThat(rounds).extracting(UnderstandingRoundView::pollId).containsExactly(first.pollId(), second.pollId());
        assertRound(rounds.get(0), firstResult);
        assertRound(rounds.get(1), secondResult);
        // 1라운드는 마감 뒤 진행된 2라운드와 새 청중의 영향을 받지 않는다.
        assertThat(rounds.get(0).totalParticipants()).isEqualTo(10);
        assertThat(rounds.get(0).counts()).containsEntry(UnderstandingStatus.UNDERSTOOD, 5L)
                .containsEntry(UnderstandingStatus.CONFUSED, 3L)
                .containsEntry(UnderstandingStatus.NOT_UNDERSTOOD, 1L);
        assertThat(rounds.get(1).totalParticipants()).isEqualTo(11);
        assertThat(rounds.get(1).respondedParticipants()).isEqualTo(11);
        assertThat(rounds.get(1).counts()).containsEntry(UnderstandingStatus.NOT_UNDERSTOOD, 10L);
    }

    // 100명을 이해됨 40, 헷갈림 35, 모르겠음 25 로 나눈다.
    private static int group(int index) {
        return index < 40 ? 0 : index < 75 ? 1 : 2;
    }

    private void assertRound(UnderstandingRoundView round, PollResultView result) {
        assertThat(round.totalParticipants()).isEqualTo(result.totalParticipants());
        assertThat(round.respondedParticipants()).isEqualTo(result.respondedParticipants());
        for (PollOptionResultView option : result.options()) {
            assertThat(round.counts()).containsEntry(option.understandingStatus(), option.count());
        }
        long lack = round.counts().get(UnderstandingStatus.CONFUSED) + round.counts().get(UnderstandingStatus.NOT_UNDERSTOOD);
        assertThat(round.lackCount()).isEqualTo(lack);
        assertThat(round.counts().values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(round.respondedParticipants());
    }

    /** 선택지별 인원 합 = 응답자 수, 응답자 + 미응답 = 전체 참여자, 응답자는 전체를 넘지 않는다. */
    private static void assertConsistent(PollResultView result) {
        long sum = result.options().stream().mapToLong(PollOptionResultView::count).sum();
        assertThat(sum).isEqualTo(result.respondedParticipants());
        assertThat(result.respondedParticipants() + result.unrespondedParticipants()).isEqualTo(result.totalParticipants());
        assertThat(result.respondedParticipants()).isLessThanOrEqualTo(result.totalParticipants());
    }

    private static void assertCounts(PollResultView result, long first, long second, long third) {
        assertThat(result.options()).extracting(PollOptionResultView::count).containsExactly(first, second, third);
    }

    private static void assertCode(ErrorCode expected, Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    /** 같은 순간에 시작하도록 맞춰 index 0..count-1 작업을 병렬로 실행하고, 하나라도 예외면 실패시킨다. */
    private void runConcurrently(int count, IndexedTask task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int index = i;
            futures.add(CompletableFuture.runAsync(() -> {
                ready.countDown();
                await(go);
                task.run(index);
            }, executor));
        }
        // 모든 작업이 대기 상태가 된 뒤 한꺼번에 시작한다.
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        try {
            for (var future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface IndexedTask {
        void run(int index);
    }

    private long rows(PollView target) {
        return jdbc.sql("SELECT COUNT(*) FROM poll_responses WHERE poll_id = ?").param(target.pollId()).query(Long.class).single();
    }

    private static long optionId(PollView target, int index) {
        return target.options().get(index).optionId();
    }

    private long newRoom() {
        return insert("""
                INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at)
                VALUES (?, 'test', ?, 'LIVE', ?)
                """, host, UUID.randomUUID().toString(), LocalDateTime.now(SEOUL));
    }

    private List<Long> newParticipants(long roomId, int count) {
        return IntStream.range(0, count).mapToObj(i -> newParticipant(roomId)).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
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
