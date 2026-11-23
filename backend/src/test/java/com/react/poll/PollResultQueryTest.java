package com.react.poll;

import com.react.poll.dto.CreatePollRequest;
import com.react.poll.dto.PollView;
import com.react.poll.entity.PollType;
import com.react.poll.repository.PollQueryRepository;
import com.react.poll.repository.PollResponseRepository;
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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 결과 집계에 쓰는 두 쿼리: 선택지별 응답 인원, 방의 누적 참여자 수. */
@SpringBootTest
@ActiveProfiles("test")
class PollResultQueryTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired PollService service;
    @Autowired PollResponseRepository responses;
    @Autowired PollQueryRepository rooms;
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
    void 선택지별_응답_인원을_센다() {
        respond(room, poll, optionId(poll, 0));
        respond(room, poll, optionId(poll, 0));
        respond(room, poll, optionId(poll, 1));

        assertThat(countsOf(poll)).containsEntry(optionId(poll, 0), 2L).containsEntry(optionId(poll, 1), 1L);
    }

    @Test
    void 응답이_없는_선택지는_결과에_나오지_않는다() {
        respond(room, poll, optionId(poll, 0));

        assertThat(countsOf(poll)).doesNotContainKey(optionId(poll, 2));
    }

    @Test
    void 선택을_바꾼_청중은_마지막_선택에만_한_번_센다() {
        long participant = newParticipant(room);
        service.respond(room, poll.pollId(), participant, optionId(poll, 0));
        service.respond(room, poll.pollId(), participant, optionId(poll, 1));

        assertThat(countsOf(poll)).containsOnly(Map.entry(optionId(poll, 1), 1L));
    }

    @Test
    void 다른_투표의_응답은_세지_않는다() {
        // 열린 투표는 방마다 하나라 다른 방에 투표를 하나 더 만든다.
        long otherRoom = newRoom();
        PollView other = service.create(otherRoom, hostEmail, new CreatePollRequest(PollType.UNDERSTANDING, null, null, 60));
        respond(room, poll, optionId(poll, 0));
        respond(otherRoom, other, optionId(other, 0));
        respond(otherRoom, other, optionId(other, 0));

        assertThat(countsOf(poll)).containsOnly(Map.entry(optionId(poll, 0), 1L));
    }

    @Test
    void 방의_누적_참여자_수를_센다_다른_방_참여자는_제외() {
        newParticipant(room);
        newParticipant(room);
        newParticipant(room);
        newParticipant(newRoom());

        assertThat(rooms.countParticipants(room)).isEqualTo(3);
        assertThat(rooms.countParticipants(newRoom())).isZero();
    }

    private Map<Long, Long> countsOf(PollView target) {
        return responses.countByOption(target.pollId()).stream()
                .collect(Collectors.toMap(PollResponseRepository.OptionCount::getOptionId,
                        PollResponseRepository.OptionCount::getCount));
    }

    private void respond(long roomId, PollView target, long optionId) {
        service.respond(roomId, target.pollId(), newParticipant(roomId), optionId);
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
