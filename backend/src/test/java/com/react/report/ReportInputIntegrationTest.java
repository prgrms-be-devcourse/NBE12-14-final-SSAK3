package com.react.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.domain.TopicCategory;
import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.report.dto.ReportContent;
import com.react.report.dto.ReportInput;
import com.react.report.service.ReportGenerationException;
import com.react.report.service.ReportInputService;
import com.react.report.service.ReportOverviewClient;
import com.react.report.service.ReportOverviewService;
import com.react.report.service.ReportOverviewValidator;
import com.react.stt.dto.SttRecordsView;
import com.react.stt.service.SttSessionService;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
    @Autowired SttSessionService stt;
    @Autowired ObjectMapper objectMapper;
    @Autowired ReportOverviewValidator validator;
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
    void 분별_타임라인에_같은_방의_원문과_분석_근거_및_마감_집계만_연결한다() {
        Fixture a = fillLecture(room(90));
        Fixture b = fillLecture(room(90));
        for (Fixture own : new Fixture[]{a, b}) {
            var report = service.collectTimeline(own.room(), email);
            assertThat(report.timeline()).hasSize(2);
            var first = report.timeline().getFirst();
            var last = report.timeline().getLast();
            assertThat(first.chatMessages()).extracting(c -> c.messageId()).containsExactly(own.firstChat(), own.repeatedChat());
            assertThat(last.chatMessages()).extracting(c -> c.messageId()).containsExactly(own.atBoundary(), own.atEnd());
            assertThat(first.sttSegments()).extracting(s -> s.segmentId())
                    .containsExactly(own.firstSegment(), own.secondSegment());
            assertThat(first.sttGaps()).extracting(g -> g.gapId()).containsExactly(own.gap());
            assertThat(last.sttSegments()).isEmpty();
            assertThat(last.sttGaps()).isEmpty();
            assertThat(first.polls()).singleElement().satisfies(p -> {
                assertThat(p.pollId()).isEqualTo(own.general());
                assertThat(p.closedAt()).isEqualTo(UTC_START.plusSeconds(64));
                assertThat(p.options()).extracting(o -> o.count()).containsExactly(0L, 1L);
            });
            assertThat(first.understandingChecks()).isEmpty();
            assertThat(last.polls()).isEmpty();
            assertThat(last.understandingChecks()).singleElement().satisfies(p -> {
                assertThat(p.pollId()).isEqualTo(own.check());
                assertThat(p.unrespondedParticipants()).isEqualTo(2);
                assertThat(p.lackCount()).isOne();
                assertThat(p.lowUnderstanding()).isFalse();
            });
            assertThat(first.audienceOpinions()).singleElement().satisfies(opinion -> {
                assertThat(opinion.topicGroupIds()).containsExactly(own.group());
                assertThat(opinion.sourceMessageIds()).containsExactly(own.firstChat(), own.repeatedChat());
            });
            assertThat(last.audienceOpinions()).singleElement().satisfies(opinion -> {
                assertThat(opinion.status()).isEqualTo(AiAnalysisStatus.FAILED);
                assertThat(opinion.startAt()).isEqualTo(UTC_START.plusSeconds(60));
                assertThat(opinion.endAt()).isEqualTo(UTC_START.plusSeconds(90));
                assertThat(opinion.sourceMessageIds()).isEmpty();
            });
            assertThat(report.dataLimitations()).anySatisfy(notice -> assertThat(notice).contains("분석 실패"));
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
            assertThat(service.collectTimeline(room, email).timeline()).singleElement().satisfies(window -> {
                assertThat(window.startAt()).isEqualTo(UTC_START);
                assertThat(window.endAt()).isEqualTo(UTC_START.plusSeconds(duration));
                assertThat(window.chatMessages()).extracting(c -> c.messageId()).containsExactly(message);
            });
        }
    }

    @Test
    void 강연_밖의_발언은_건수로_안내하고_저장된_발언에서는_그대로_읽을_수_있다() throws Exception {
        long room = room(38);
        analysis(room, 0, 30, "COMPLETED");
        analysis(room, 30, 38, "FAILED");
        long session = session(room, 0, 38);
        long before = segment(session, -20_000, -19_000, 5);
        long spanningStart = segment(session, -300, 200, 10);
        long after = segment(session, 45_000, 46_000, 35);
        segment(session(room(38), 0, 38), -12_000, -11_000, 5);

        var report = service.collectTimeline(room, email);
        var json = objectMapper.readTree(objectMapper.writeValueAsBytes(report));
        assertThat(json.path("timeline").size()).isOne();
        var window = json.at("/timeline/0");
        assertThat(window.path("sttSegments").size()).isOne();
        assertThat(window.at("/sttSegments/0/segmentId").asLong()).isEqualTo(spanningStart);
        assertThat(window.at("/sttSegments/0/transcript").asText()).isEqualTo("같은 발언");
        assertThat(Instant.parse(window.at("/sttSegments/0/startAt").asText())).isEqualTo(UTC_START.minusMillis(300));
        assertThat(Instant.parse(window.at("/sttSegments/0/endAt").asText())).isEqualTo(UTC_START.plusMillis(200));
        assertThat(window.at("/audienceOpinions/0/status").asText()).isEqualTo("FAILED");
        assertThat(Instant.parse(window.at("/audienceOpinions/0/startAt").asText())).isEqualTo(UTC_START.plusSeconds(30));
        assertThat(Instant.parse(window.at("/audienceOpinions/0/endAt").asText())).isEqualTo(UTC_START.plusSeconds(38));
        assertThat(report.dataLimitations()).anySatisfy(notice -> assertThat(notice).contains("강연 시간에 연결할 수 없는 발언 2건", "저장된 발언 보기"));

        var saved = stt.records(room, email, null, null);
        assertThat(saved.segments()).extracting(SttRecordsView.Segment::segmentId)
                .containsExactly(before, spanningStart, after);
        assertThat(saved.segments().getFirst().segmentStartAt()).isEqualTo(UTC_START.minusSeconds(20));
        assertThat(saved.segments().getLast().segmentEndAt()).isEqualTo(UTC_START.plusSeconds(46));
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
        assertThatThrownBy(() -> service.collectTimeline(room, hostEmail)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void 마감_후_재설명_채팅은_작성_구간에_남고_모호한_채팅은_원문으로만_보인다() {
        long room = room(90);
        analysis(room, 0, 30, "COMPLETED");
        analysis(room, 30, 60, "COMPLETED");
        analysis(room, 60, 90, "FAILED");
        long first = participant(room);
        long second = participant(room);
        long check = poll(room, "UNDERSTANDING", 35, 50, 2);
        jdbc.sql("UPDATE polls SET question = '가스비 계산을 이해하셨나요?' WHERE id = ?").param(check).update();
        vote(check, option(check, "이해됨", "UNDERSTOOD", 1), first, 40);
        vote(check, option(check, "헷갈림", "CONFUSED", 2), second, 42);
        option(check, "모르겠음", "NOT_UNDERSTOOD", 3);
        long vagueChat = chat(room, first, 60, "다시 보여주세요");
        long specificChat = chat(room, second, 90, "아까 가스비 계산 예시를 다시 보여주세요");
        long otherRoom = room(90);
        long foreignChat = chat(otherRoom, participant(otherRoom), 90);

        var client = mock(ReportOverviewClient.class);
        var draft = objectMapper.createObjectNode();
        var overview = draft.putObject("overview");
        overview.putArray("mainInterests");
        overview.putArray("suggestions");
        var item = overview.putArray("reviewPoints").addObject();
        item.put("text", "가스비 계산 예시의 재설명을 요청한 채팅이 있었습니다.");
        item.putArray("perspectives");
        var refs = item.putArray("evidence");
        refs.addObject().put("type", "POLL_RESULT").put("id", check);
        var chatRef = refs.addObject().put("type", "CHAT_MESSAGE").put("id", specificChat);
        when(client.generate(any())).thenAnswer(invocation -> {
            JsonNode payload = invocation.getArgument(0);
            var messages = payload.get("evidenceRecords").valueStream()
                    .filter(r -> r.path("type").asText().equals("CHAT_MESSAGE")).toList();
            assertThat(messages).extracting(r -> r.path("id").longValue())
                    .containsExactly(vagueChat, specificChat).doesNotContain(foreignChat);
            assertThat(messages).allSatisfy(r -> assertThat(r.at("/data/participantId").asText()).matches("p[12]"));
            item.set("relatedWindows", payload.get("windows"));
            return draft;
        });
        var reports = new ReportOverviewService(service, client, validator, objectMapper);
        var content = reports.generate(room, email);
        assertThat(content.overview().at("/reviewPoints/0/evidence")).hasSize(2);
        assertThat(content.timeline().getFirst().understandingChecks()).singleElement()
                .satisfies(p -> assertThat(p.closedAt()).isEqualTo(UTC_START.plusSeconds(50)));
        assertThat(content.timeline().getLast().chatMessages()).extracting(c -> c.messageId())
                .containsExactly(vagueChat, specificChat);
        assertThat(content.timeline().getLast().chatMessages().getLast().createdAt()).isEqualTo(UTC_START.plusSeconds(90));
        assertThat(content.dataLimitations()).anyMatch(note -> note.contains("분석 실패"));
        verify(client).generate(any());

        chatRef.put("id", foreignChat);
        assertThatThrownBy(() -> reports.generate(room, email)).isInstanceOfSatisfying(ReportGenerationException.class,
                e -> assertThat(e.reason()).isEqualTo(ReportGenerationException.Reason.INVALID_REPORT_EVIDENCE));
        verify(client, times(2)).generate(any());
    }

    @Test
    void 기록이_없거나_청중_응답이_없으면_LLM을_호출하지_않고_원문과_빈_요약을_반환한다() {
        long room = room(10);
        analysis(room, 0, 10, "COMPLETED");
        var client = mock(ReportOverviewClient.class);
        var reports = new ReportOverviewService(service, client, validator, objectMapper);
        var empty = reports.generate(room, email);
        assertThat(empty.dataLimitations()).anyMatch(note -> note.contains("수집된 기록이 없어"));
        segment(session(room, 0, 8), 100, 200, 1);
        long silentPoll = poll(room, "GENERAL", 2, 7, 1);
        option(silentPoll, "예제", null, 1);
        var sttOnly = reports.generate(room, email);
        for (ReportContent result : List.of(empty, sttOnly)) {
            assertThat(result.overview().properties()).hasSize(3);
            assertThat(result.overview()).allMatch(node -> node.isArray() && node.isEmpty());
        }
        assertThat(sttOnly.timeline().getFirst().sttSegments()).hasSize(1);
        assertThat(sttOnly.timeline().getFirst().polls().getFirst().respondedParticipants()).isZero();
        assertThat(sttOnly.dataLimitations()).anyMatch(note -> note.contains("청중 반응 기록이 부족"));
        verifyNoInteractions(client);
    }

    private record Fixture(long room, long firstChat, long repeatedChat, long atBoundary, long atEnd,
                           long firstSession, long secondSession, long firstSegment, long secondSegment,
                           long gap, long group, long general, long check) {}

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
        long secondSegment = segment(next, 31_120, 32_120, 33);
        long firstSegment = segment(session, 120, 1_120, 2);
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
        return new Fixture(room, firstChat, repeated, boundary, end, session, next, firstSegment, secondSegment,
                gap, group, general, check);
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

    private long segment(long session, int startMillis, int endMillis, int receivedSecond) {
        var start = START.plusNanos(startMillis * 1_000_000L);
        var end = START.plusNanos(endMillis * 1_000_000L);
        return insert("""
                INSERT INTO stt_segments(stt_session_id,segment_key,transcript,segment_start_at,segment_end_at,received_at)
                VALUES (?, ?, '같은 발언', ?, ?, ?)
                """, session, UUID.randomUUID().toString(), start, end, START.plusSeconds(receivedSecond));
    }

    private long chat(long room, long participant, int second) {
        return chat(room, participant, second, "예제 다시 보여주세요");
    }

    private long chat(long room, long participant, int second, String text) {
        return insert("""
                INSERT INTO chat_messages(lecture_room_id,participant_id,content,received_at) VALUES (?, ?, ?, ?)
                """, room, participant, text, START.plusSeconds(second));
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
