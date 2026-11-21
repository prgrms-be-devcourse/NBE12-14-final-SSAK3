package com.react.report;

import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.domain.TopicCategory;
import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.report.dto.ReportInput;
import com.react.report.service.ReportInputService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportInputIntegrationTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 21, 10, 0);
    private static final Instant UTC_START = START.toInstant(ZoneOffset.ofHours(9));

    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired ReportInputService service;
    private String email;
    private long host;

    @BeforeAll
    void migrateTestDatabase() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).isEqualTo("react_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @BeforeEach
    void createHost() {
        email = UUID.randomUUID() + "@example.com";
        host = insert("INSERT INTO users(email,password_hash,name,created_at) VALUES (?, 'hash', 'host', ?)", email, START);
    }

    @Test
    void 같은_강사가_운영한_동시간대_두_방의_모든_원문과_집계를_구분한다() {
        long firstRoom = room(90);
        long secondRoom = room(90);
        Fixture a = fillLecture(firstRoom);
        Fixture b = fillLecture(secondRoom);
        for (Fixture own : new Fixture[]{a, b}) {
            var input = service.collect(own.room(), email);
            assertThat(input.chats()).extracting(ReportInput.Chat::messageId)
                    .containsExactly(own.firstChat(), own.repeatedChat(), own.atBoundary(), own.atEnd());
            assertThat(input.chats()).extracting(ReportInput.Chat::participantId)
                    .containsExactly("p1", "p1", "p2", "p1");
            assertThat(input.chats().getFirst().text()).isEqualTo("예제 다시 보여주세요");
            assertThat(input.chats().get(2).window()).isEqualTo(input.chats().getLast().window())
                    .isEqualTo(new ReportInput.Window(UTC_START.plusSeconds(60), UTC_START.plusSeconds(90)));

            assertThat(input.stt().segments()).extracting(s -> s.sessionId())
                    .containsExactly(own.firstSession(), own.secondSession());
            assertThat(input.stt().segments().getFirst().segmentStartAt()).isEqualTo(UTC_START.plusMillis(120));
            assertThat(input.stt().gaps()).singleElement().satisfies(g -> assertThat(g.gapId()).isEqualTo(own.gap()));

            assertThat(input.analyses()).extracting(ReportInput.Analysis::status)
                    .containsExactly(AiAnalysisStatus.COMPLETED, AiAnalysisStatus.COMPLETED, AiAnalysisStatus.FAILED);
            assertThat(input.analyses().get(1).topics()).isEmpty();
            assertThat(input.analyses().get(2).topics()).isEmpty();
            assertThat(input.analyses().getFirst().topics()).singleElement().satisfies(topic -> {
                assertThat(topic.category()).isEqualTo(TopicCategory.REQUEST);
                assertThat(topic.groupId()).isEqualTo(own.group());
                assertThat(topic.messageIds()).containsExactly(own.firstChat(), own.repeatedChat());
                assertThat(topic.messageCount()).isEqualTo(2);
            });

            assertThat(input.polls()).extracting(ReportInput.Poll::pollId).containsExactly(own.general(), own.check());
            var poll = input.polls().getFirst();
            assertThat(poll.window()).isEqualTo(new ReportInput.Window(UTC_START, UTC_START.plusSeconds(60)));
            assertThat(poll.closedAt()).isEqualTo(UTC_START.plusSeconds(64));
            assertThat(poll.totalParticipants()).isEqualTo(2);
            assertThat(poll.respondedParticipants()).isOne();
            assertThat(poll.unrespondedParticipants()).isOne();
            assertThat(poll.responseRate()).isEqualTo(50.0);
            assertThat(poll.options()).extracting(o -> o.count()).containsExactly(0L, 1L);
            assertThat(poll.options().get(1).percentageOfResponses()).isEqualTo(100.0);
            assertThat(poll.options().get(1).percentageOfAllParticipants()).isEqualTo(50.0);
            var check = input.polls().getLast();
            assertThat(check.totalParticipants()).isEqualTo(3);
            assertThat(check.understanding()).isEqualTo(new ReportInput.Understanding(1, 100.0, false));
        }
    }

    @Test
    void 종료가_정확히_분_경계일_때와_시작과_같은_초일_때_마지막_채팅을_한번만_수집한다() {
        for (int duration : new int[]{60, 0}) {
            long room = room(duration);
            for (int second = 0; second < duration; second += 30) {
                analysis(room, second, second + 30, "COMPLETED");
            }
            long message = chat(room, participant(room), duration);
            var input = service.collect(room, email);
            assertThat(input.chats()).singleElement().satisfies(chat -> {
                assertThat(chat.messageId()).isEqualTo(message);
                assertThat(chat.window()).isEqualTo(new ReportInput.Window(UTC_START, UTC_START.plusSeconds(duration)));
            });
        }
    }

    @Test
    void 다른_방의_채팅을_근거로_연결한_잘못된_주제는_내보내지_않는다() {
        Fixture a = fillLecture(room(90));
        Fixture b = fillLecture(room(90));
        link(a.group(), b.firstChat());
        expectError(a.room(), email, ErrorCode.REPORT_INPUT_NOT_READY);
    }

    @Test
    void 방_소유권과_종료_확정_여부를_먼저_확인한다() {
        long room = room(1);
        expectError(room, "other@example.com", ErrorCode.FORBIDDEN);
        jdbc.sql("UPDATE lecture_rooms SET status = 'LIVE' WHERE id = ?").param(room).update();
        expectError(room, email, ErrorCode.ROOM_NOT_ENDED);
        expectError(-1, email, ErrorCode.ROOM_NOT_FOUND);
    }

    @Test
    void 잔여_분석과_마감_집계가_준비된_뒤에만_입력을_반환한다() {
        long room = room(4);
        expectError(room, email, ErrorCode.REPORT_INPUT_NOT_READY);
        long analysis = analysis(room, 0, 4, "PROCESSING");
        expectError(room, email, ErrorCode.REPORT_INPUT_NOT_READY);
        jdbc.sql("UPDATE ai_analyses SET status = 'FAILED' WHERE id = ?").param(analysis).update();
        var input = service.collect(room, email);
        assertThat(input.chats()).isEmpty();
        assertThat(input.stt().segments()).isEmpty();
        assertThat(input.analyses()).singleElement().satisfies(a -> assertThat(a.status()).isEqualTo(AiAnalysisStatus.FAILED));
        long poll = poll(room, "GENERAL", 0, 3, 0);
        jdbc.sql("UPDATE polls SET closed_total_participants = NULL WHERE id = ?").param(poll).update();
        expectError(room, email, ErrorCode.REPORT_INPUT_NOT_READY);
        jdbc.sql("UPDATE polls SET status = 'OPEN' WHERE id = ?").param(poll).update();
        expectError(room, email, ErrorCode.REPORT_INPUT_NOT_READY);
    }

    private void expectError(long room, String hostEmail, ErrorCode code) {
        assertThatThrownBy(() -> service.collect(room, hostEmail)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private record Fixture(long room, long firstChat, long repeatedChat, long atBoundary, long atEnd,
                           long firstSession, long secondSession, long gap, long group, long general, long check) {}

    private Fixture fillLecture(long room) {
        long first = participant(room);
        long second = participant(room);
        long firstChat = chat(room, first, 2);
        long repeated = chat(room, first, 2);
        long boundary = chat(room, second, 60);
        long end = chat(room, first, 90);
        jdbc.sql("INSERT INTO chat_messages(lecture_room_id,host_user_id,content,received_at) VALUES (?, ?, '강연자 답변', ?)")
                .params(room, host, START).update();

        long session = session(room, 0, 20);
        long next = session(room, 30, 55);
        segment(next, 31);
        segment(session, 0);
        long gap = insert("""
                INSERT INTO stt_gaps(stt_session_id,event_key,started_at,ended_at,reason)
                VALUES (?, 'gap1', ?, ?, 'ERROR')
                """, session, START.plusSeconds(8), START.plusSeconds(10));

        long done = analysis(room, 0, 30, "COMPLETED");
        analysis(room, 60, 90, "FAILED");
        analysis(room, 30, 60, "COMPLETED");
        long group = insert("""
                INSERT INTO ai_topic_groups(analysis_id,topic,category,summary,created_at)
                VALUES (?, '예제', 'REQUEST', '예제 요청', ?)
                """, done, START.plusSeconds(30));
        link(group, firstChat);
        link(group, repeated);

        long general = poll(room, "GENERAL", 48, 64, 2);
        option(general, "설명", null, 1);
        long example = option(general, "예제", null, 2);
        vote(general, example, first, 57);
        participant(room);
        long check = poll(room, "UNDERSTANDING", 68, 89, 3);
        option(check, "이해됨", "UNDERSTOOD", 1);
        long confused = option(check, "헷갈림", "CONFUSED", 2);
        option(check, "모르겠음", "NOT_UNDERSTOOD", 3);
        vote(check, confused, second, 73);
        return new Fixture(room, firstChat, repeated, boundary, end, session, next, gap, group, general, check);
    }

    private long room(int duration) {
        return insert("""
                INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at,started_at,ended_at)
                VALUES (?, '같은 제목', ?, 'ENDED', ?, ?, ?)
                """, host, UUID.randomUUID().toString(), START, START, START.plusSeconds(duration));
    }

    private long participant(long room) {
        return insert("""
                INSERT INTO lecture_participants(lecture_room_id,nickname,participant_token_hash,joined_at)
                VALUES (?, '동일 닉네임', ?, ?)
                """, room, UUID.randomUUID().toString(), START);
    }

    private long session(long room, int start, int end) {
        return insert("""
                INSERT INTO stt_sessions(lecture_room_id,status,started_at,ended_at) VALUES (?, 'STOPPED', ?, ?)
                """, room, START.plusSeconds(start), START.plusSeconds(end));
    }

    private void segment(long session, int second) {
        var start = START.plusSeconds(second).plusNanos(120_000_000);
        jdbc.sql("""
                INSERT INTO stt_segments(stt_session_id,segment_key,transcript,segment_start_at,segment_end_at,received_at)
                VALUES (?, 'seg1', '같은 발언', ?, ?, ?)
                """).params(session, start, start.plusSeconds(1), start.plusSeconds(2)).update();
    }

    private long chat(long room, long participant, int second) {
        return insert("""
                INSERT INTO chat_messages(lecture_room_id,participant_id,content,received_at) VALUES (?, ?, '예제 다시 보여주세요', ?)
                """, room, participant, START.plusSeconds(second));
    }

    private long analysis(long room, int start, int end, String status) {
        return insert("""
                INSERT INTO ai_analyses(lecture_room_id,window_start_at,window_end_at,status,created_at) VALUES (?, ?, ?, ?, ?)
                """, room, START.plusSeconds(start), START.plusSeconds(end), status, START);
    }

    private void link(long group, long chat) {
        jdbc.sql("INSERT INTO ai_topic_group_messages(topic_group_id,chat_message_id) VALUES (?, ?)")
                .params(group, chat).update();
    }

    private long poll(long room, String type, int start, int end, long total) {
        return insert("""
                INSERT INTO polls(lecture_room_id,type,question,status,created_at,closes_at,closed_at,closed_total_participants)
                VALUES (?, ?, '같은 질문', 'CLOSED', ?, ?, ?, ?)
                """, room, type, START.plusSeconds(start), START.plusSeconds(end), START.plusSeconds(end), total);
    }

    private long option(long poll, String text, String status, int order) {
        return insert("""
                INSERT INTO poll_options(poll_id,content,understanding_status,display_order) VALUES (?, ?, ?, ?)
                """, poll, text, status, order);
    }

    private void vote(long poll, long option, long participant, int second) {
        jdbc.sql("INSERT INTO poll_responses(poll_id,poll_option_id,participant_id,responded_at) VALUES (?, ?, ?, ?)")
                .params(poll, option, participant, START.plusSeconds(second)).update();
    }

    private long insert(String sql, Object... params) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql(sql).params(params).update(keys);
        return keys.getKey().longValue();
    }
}
