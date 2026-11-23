package com.react.report;

import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.domain.TopicCategory;
import com.react.report.dto.ReportInput;
import com.react.report.service.ReportTimelineAssembler;
import com.react.stt.dto.SttGap;
import com.react.stt.dto.SttRecordsView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportTimelineAssemblerTest {
    private static final Instant START = Instant.parse("2026-11-21T01:07:13Z");

    @Test
    void 발언과_누락은_겹치는_분에만_원래_시각으로_연결하고_정각의_점은_한_번만_둔다() {
        var records = new SttRecordsView(1, List.of(
                segment(6, -1, 0), segment(1, 0, 60), segment(2, 59, 61), segment(3, 60, 60),
                segment(4, 120, 120), segment(5, 125, 125), segment(7, 125, 126)), List.of(
                gap(1, 54, null), gap(2, 60, 60), gap(3, 73, 120)));
        var input = new ReportInput(1, START, START.plusSeconds(125), records, List.of(), List.of(), List.of());

        var timeline = ReportTimelineAssembler.assemble(input).timeline();
        assertThat(timeline).extracting(w -> w.startAt()).containsExactly(START, START.plusSeconds(60), START.plusSeconds(120));
        assertThat(timeline).extracting(w -> w.endAt()).containsExactly(START.plusSeconds(60), START.plusSeconds(120), START.plusSeconds(125));
        var first = timeline.getFirst();
        var second = timeline.get(1);
        var last = timeline.getLast();
        assertThat(first.sttSegments()).extracting(s -> s.segmentId()).containsExactly(1L, 2L);
        assertThat(second.sttSegments()).extracting(s -> s.segmentId()).containsExactly(2L, 3L);
        assertThat(last.sttSegments()).extracting(s -> s.segmentId()).containsExactly(4L, 5L);
        var crossing = first.sttSegments().getLast();
        assertThat(crossing).isEqualTo(second.sttSegments().getFirst());
        assertThat(crossing.startAt()).isEqualTo(START.plusSeconds(59));
        assertThat(crossing.endAt()).isEqualTo(START.plusSeconds(61));
        assertThat(crossing.transcript()).isEqualTo("원래 발언 2");
        assertThat(first.sttGaps()).containsExactly(records.gaps().getFirst());
        assertThat(second.sttGaps()).containsExactlyElementsOf(records.gaps());
        assertThat(last.sttGaps()).containsExactly(records.gaps().getFirst());
        assertThat(last.sttGaps().getFirst().endedAt()).isNull();
    }

    @Test
    void 기록이_없는_분은_유지하되_시작과_종료가_같은_빈_방은_구간을_만들지_않는다() {
        var empty = new SttRecordsView(1, List.of(), List.of());
        var room = new ReportInput(1, START, START.plusSeconds(120), empty, List.of(), List.of(), List.of());
        var timeline = ReportTimelineAssembler.assemble(room).timeline();
        assertThat(timeline).hasSize(2).allSatisfy(window -> {
            assertThat(window.sttSegments()).isEmpty();
            assertThat(window.sttGaps()).isEmpty();
        });
        var sameInstant = new ReportInput(1, START, START, empty, List.of(), List.of(), List.of());
        assertThat(ReportTimelineAssembler.assemble(sameInstant).timeline()).isEmpty();
    }

    @Test
    void 시작과_종료가_같은_방은_그_순간을_포함하는_발언과_열린_누락만_연결한다() {
        var records = new SttRecordsView(1, List.of(segment(1, -2, 0), segment(2, -1, 1), segment(3, 0, 0)),
                List.of(gap(1, -3, 0), gap(2, -2, null)));
        var input = new ReportInput(1, START, START, records, List.of(), List.of(), List.of());

        assertThat(ReportTimelineAssembler.assemble(input).timeline()).singleElement().satisfies(window -> {
            assertThat(window.startAt()).isEqualTo(START);
            assertThat(window.endAt()).isEqualTo(START);
            assertThat(window.sttSegments()).extracting(s -> s.segmentId()).containsExactly(2L, 3L);
            assertThat(window.sttGaps()).containsExactly(records.gaps().getLast());
            assertThat(window.sttGaps().getFirst().endedAt()).isNull();
        });
    }

    @Test
    void 같은_분에_같은_주제가_두_번_나와도_각_분석의_시간과_근거를_유지한다() {
        var firstTopic = new ReportInput.Topic(51, "예제", TopicCategory.REQUEST, "첫 예제 요청", List.of(31L));
        var nextTopic = new ReportInput.Topic(52, "예제", TopicCategory.REQUEST, "추가 예제 요청", List.of(32L));
        Instant middle = START.plusSeconds(30);
        Instant end = START.plusSeconds(60);
        var first = new ReportInput.Analysis(11, START, middle, AiAnalysisStatus.COMPLETED, List.of(firstTopic));
        var next = new ReportInput.Analysis(12, middle, end, AiAnalysisStatus.COMPLETED, List.of(nextTopic));
        var window = new ReportInput.Window(START, end);
        var chats = List.of(new ReportInput.Chat(31, "p1", "예제를 보여주세요", START, window),
                new ReportInput.Chat(32, "p2", "다른 예제도 궁금해요", middle, window));
        var input = new ReportInput(1, START, end, new SttRecordsView(1, List.of(), List.of()),
                chats, List.of(first, next), List.of());

        var opinions = ReportTimelineAssembler.assemble(input).timeline().getFirst().audienceOpinions();
        assertThat(opinions).extracting(o -> o.analysisId()).containsExactly(11L, 12L);
        assertThat(opinions).extracting(o -> o.startAt()).containsExactly(START, middle);
        assertThat(opinions).extracting(o -> o.endAt()).containsExactly(middle, end);
        assertThat(opinions).extracting(o -> o.topicGroupIds()).containsExactly(List.of(51L), List.of(52L));
        assertThat(opinions).extracting(o -> o.sourceMessageIds()).containsExactly(List.of(31L), List.of(32L));
    }

    private static SttRecordsView.Segment segment(long id, int start, int end) {
        return new SttRecordsView.Segment(id, 1, "원래 발언 " + id, START.plusSeconds(start),
                START.plusSeconds(end), START.plusSeconds(end + 1));
    }

    private static SttRecordsView.Gap gap(long id, int start, Integer end) {
        return new SttRecordsView.Gap(id, id, SttGap.Reason.ERROR, START.plusSeconds(start),
                end == null ? null : START.plusSeconds(end));
    }
}
