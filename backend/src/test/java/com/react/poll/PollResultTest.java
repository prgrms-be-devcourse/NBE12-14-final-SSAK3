package com.react.poll;

import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.poll.dto.CreatePollRequest;
import com.react.poll.dto.PollOptionResultView;
import com.react.poll.dto.PollResultView;
import com.react.poll.dto.PollView;
import com.react.poll.entity.PollStatus;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 투표 결과 집계: 인원·비율 규칙, 방 격리와 소유자 확인, 마감 후 불변을 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
class PollResultTest {
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
    void 명세_예시_그대로_인원과_비율을_계산한다() {
        // 참여자 20명 중 12명이 응답: 이해됨 6, 헷갈림 4, 모르겠음 2
        List<Long> participants = newParticipants(room, 20);
        respondAll(participants.subList(0, 6), 0);
        respondAll(participants.subList(6, 10), 1);
        respondAll(participants.subList(10, 12), 2);

        PollResultView result = service.results(room, poll.pollId(), hostEmail);

        assertThat(result.pollId()).isEqualTo(poll.pollId());
        assertThat(result.type()).isEqualTo(PollType.UNDERSTANDING);
        assertThat(result.status()).isEqualTo(PollStatus.OPEN);
        assertThat(result.closesAt()).isEqualTo(poll.closesAt());
        assertThat(result.totalParticipants()).isEqualTo(20);
        assertThat(result.respondedParticipants()).isEqualTo(12);
        assertThat(result.unrespondedParticipants()).isEqualTo(8);
        assertThat(result.responseRate()).isEqualTo(60.0);
        assertThat(result.calculatedAt()).isNotNull();

        PollOptionResultView understood = result.options().get(0);
        assertThat(understood.understandingStatus()).isEqualTo(UnderstandingStatus.UNDERSTOOD);
        assertThat(understood.count()).isEqualTo(6);
        assertThat(understood.percentageOfResponses()).isEqualTo(50.0);
        assertThat(understood.percentageOfAllParticipants()).isEqualTo(30.0);
        PollOptionResultView confused = result.options().get(1);
        assertThat(confused.count()).isEqualTo(4);
        assertThat(confused.percentageOfResponses()).isEqualTo(33.3);
        assertThat(confused.percentageOfAllParticipants()).isEqualTo(20.0);
        PollOptionResultView notUnderstood = result.options().get(2);
        assertThat(notUnderstood.count()).isEqualTo(2);
        assertThat(notUnderstood.percentageOfResponses()).isEqualTo(16.7);
        assertThat(notUnderstood.percentageOfAllParticipants()).isEqualTo(10.0);
    }

    @Test
    void 선택지별_인원의_합은_응답자_수와_같고_응답자와_미응답의_합은_전체와_같다() {
        List<Long> participants = newParticipants(room, 7);
        respondAll(participants.subList(0, 3), 0);
        respondAll(participants.subList(3, 5), 2);

        PollResultView result = service.results(room, poll.pollId(), hostEmail);

        long sum = result.options().stream().mapToLong(PollOptionResultView::count).sum();
        assertThat(sum).isEqualTo(result.respondedParticipants()).isEqualTo(5);
        assertThat(result.respondedParticipants() + result.unrespondedParticipants())
                .isEqualTo(result.totalParticipants()).isEqualTo(7);
    }

    @Test
    void 응답이_없으면_모든_선택지가_0이고_미응답은_전체_참여자이다() {
        newParticipants(room, 3);

        PollResultView result = service.results(room, poll.pollId(), hostEmail);

        assertThat(result.respondedParticipants()).isZero();
        assertThat(result.unrespondedParticipants()).isEqualTo(3);
        assertThat(result.responseRate()).isEqualTo(0.0);
        assertThat(result.options()).hasSize(3).allSatisfy(option -> {
            assertThat(option.count()).isZero();
            assertThat(option.percentageOfResponses()).isEqualTo(0.0);
            assertThat(option.percentageOfAllParticipants()).isEqualTo(0.0);
        });
    }

    @Test
    void 참여자가_없어도_0으로_나누지_않고_결과를_준다() {
        PollResultView result = service.results(room, poll.pollId(), hostEmail);

        assertThat(result.totalParticipants()).isZero();
        assertThat(result.unrespondedParticipants()).isZero();
        assertThat(result.responseRate()).isEqualTo(0.0);
        assertThat(result.options()).allSatisfy(option -> assertThat(option.percentageOfAllParticipants()).isEqualTo(0.0));
    }

    @Test
    void 일반_투표는_understandingStatus_없이_선택지_순서대로_나온다() {
        PollView general = createGeneral(newRoom());
        List<Long> participants = newParticipants(general.roomId(), 2);
        service.respond(general.roomId(), general.pollId(), participants.get(0), general.options().get(1).optionId());
        service.respond(general.roomId(), general.pollId(), participants.get(1), general.options().get(1).optionId());

        PollResultView result = service.results(general.roomId(), general.pollId(), hostEmail);

        assertThat(result.type()).isEqualTo(PollType.GENERAL);
        assertThat(result.options()).extracting(PollOptionResultView::content).containsExactly("A", "B");
        assertThat(result.options()).allSatisfy(option -> assertThat(option.understandingStatus()).isNull());
        assertThat(result.options().get(0).count()).isZero();
        assertThat(result.options().get(1).count()).isEqualTo(2);
        assertThat(result.options().get(1).percentageOfResponses()).isEqualTo(100.0);
    }

    @Test
    void 선택을_바꾸면_결과에_반영되고_응답자_수는_그대로이다() {
        long participant = newParticipants(room, 1).get(0);
        service.respond(room, poll.pollId(), participant, optionId(poll, 0));
        service.respond(room, poll.pollId(), participant, optionId(poll, 1));

        PollResultView result = service.results(room, poll.pollId(), hostEmail);

        assertThat(result.respondedParticipants()).isEqualTo(1);
        assertThat(result.options().get(0).count()).isZero();
        assertThat(result.options().get(1).count()).isEqualTo(1);
    }

    @Test
    void 마감하면_이후_응답은_거절되어_결과가_변하지_않는다() {
        List<Long> participants = newParticipants(room, 3);
        service.respond(room, poll.pollId(), participants.get(0), optionId(poll, 0));
        service.close(room, poll.pollId(), hostEmail);
        PollResultView before = service.results(room, poll.pollId(), hostEmail);

        assertThatThrownBy(() -> service.respond(room, poll.pollId(), participants.get(1), optionId(poll, 1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.POLL_CLOSED));
        PollResultView after = service.results(room, poll.pollId(), hostEmail);

        assertThat(before.status()).isEqualTo(PollStatus.CLOSED);
        assertThat(after.respondedParticipants()).isEqualTo(before.respondedParticipants()).isEqualTo(1);
        assertThat(after.options()).isEqualTo(before.options());
    }

    @Test
    void 방_소유자가_아닌_강연자는_조회할_수_없다() {
        String stranger = UUID.randomUUID() + "@example.com";
        insert("INSERT INTO users(email,password_hash,name,created_at) VALUES (?, 'hash', '다른 강연자', ?)",
                stranger, LocalDateTime.now(SEOUL));

        assertCode(ErrorCode.FORBIDDEN, () -> service.results(room, poll.pollId(), stranger));
    }

    @Test
    void 다른_방의_투표_번호로는_조회할_수_없다() {
        long otherRoom = newRoom();

        assertCode(ErrorCode.POLL_NOT_FOUND, () -> service.results(otherRoom, poll.pollId(), hostEmail));
    }

    @Test
    void 없는_투표와_없는_방은_찾을_수_없다() {
        assertCode(ErrorCode.POLL_NOT_FOUND, () -> service.results(room, poll.pollId() + 99_999, hostEmail));
        assertCode(ErrorCode.ROOM_NOT_FOUND, () -> service.results(room + 99_999, poll.pollId(), hostEmail));
    }

    private PollView createGeneral(long roomId) {
        return service.create(roomId, hostEmail, new CreatePollRequest(PollType.GENERAL, "어느 쪽?", List.of("A", "B"), 60));
    }

    private void respondAll(List<Long> participants, int optionIndex) {
        participants.forEach(participant ->
                service.respond(room, poll.pollId(), participant, optionId(poll, optionIndex)));
    }

    private static void assertCode(ErrorCode expected, Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
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
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> newParticipant(roomId)).toList();
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
