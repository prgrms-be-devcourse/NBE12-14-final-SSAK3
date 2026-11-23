package com.react.report.service;

import com.react.ai.domain.AiAnalysisStatus;
import com.react.poll.entity.PollStatus;
import com.react.poll.entity.PollType;
import com.react.report.dto.ReportInput;
import com.react.report.dto.ReportTimeline;
import com.react.stt.dto.SttRecordsView;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** 한 강연의 읽기 스냅샷만 사용한다. DB 조회나 AI 호출을 하지 않는다. */
public final class ReportTimelineAssembler {
    private static final DateTimeFormatter KOREAN_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Seoul"));
    private final Instant roomStart;
    private final Instant roomEnd;
    private final List<WindowData> windows = new ArrayList<>();
    private final List<String> limitations = new ArrayList<>();

    public static ReportTimeline assemble(ReportInput input) {
        return new ReportTimelineAssembler(input.startedAt(), input.endedAt()).assembleRecords(input);
    }

    private ReportTimelineAssembler(Instant roomStart, Instant roomEnd) {
        this.roomStart = roomStart;
        this.roomEnd = roomEnd;
        Instant start = roomStart;
        do {
            var window = ReportInput.Window.forPoint(start, roomStart, roomEnd);
            windows.add(new WindowData(window));
            start = window.endAt();
        } while (start.isBefore(roomEnd));
    }

    private ReportTimeline assembleRecords(ReportInput input) {
        long unplaced = 0;
        for (var segment : input.stt().segments()) {
            var record = new ReportTimeline.Segment(segment.segmentId(), segment.transcript(),
                    segment.segmentStartAt(), segment.segmentEndAt());
            if (!placeInterval(record.startAt(), record.endAt(), window -> window.segments.add(record))) {
                unplaced++;
            }
        }
        if (unplaced > 0) {
            limitations.add("강연 시간에 연결할 수 없는 발언 %d건이 있습니다. 원문은 강연방의 ‘저장된 발언 보기’에서 확인할 수 있습니다."
                    .formatted(unplaced));
        }
        for (var gap : input.stt().gaps()) {
            if (placeInterval(gap.startedAt(), gap.endedAt(), window -> window.gaps.add(gap))) {
                limitations.add(gapLimitation(gap));
            }
        }
        for (var chat : input.chats()) {
            windowAt(chat.receivedAt()).ifPresent(window -> window.chats.add(
                    new ReportTimeline.Chat(chat.messageId(), chat.text(), chat.receivedAt())));
        }
        for (var poll : input.polls()) {
            windowAt(poll.openedAt()).ifPresent(window -> {
                var destination = poll.type() == PollType.UNDERSTANDING ? window.understandingChecks : window.polls;
                destination.add(pollResult(poll));
            });
        }
        for (var analysis : input.analyses()) {
            windowAt(analysis.startAt()).ifPresent(window -> addOpinions(window, analysis));
        }
        if (input.chats().isEmpty() && input.polls().stream().noneMatch(poll -> poll.respondedParticipants() > 0)) {
            limitations.add(input.stt().segments().isEmpty() ? "수집된 기록이 없어 요약할 수 없습니다."
                    : "청중 반응 기록이 부족해 관심사와 보충 설명 지점을 판단하기 어렵습니다.");
        }
        var timeline = windows.stream()
                .filter(window -> !roomStart.equals(roomEnd) || window.hasRecords())
                .map(WindowData::snapshot).toList();
        return new ReportTimeline(timeline, List.copyOf(limitations));
    }

    private Optional<WindowData> windowAt(Instant point) {
        if (point.isBefore(roomStart) || point.isAfter(roomEnd)) {
            return Optional.empty();
        }
        return Optional.of(windows.get(windowIndex(point)));
    }

    private int windowIndex(Instant point) {
        return Math.toIntExact(ReportInput.Window.indexForPoint(point, roomStart, roomEnd));
    }

    private boolean placeInterval(Instant start, Instant end, Consumer<WindowData> place) {
        if (start.equals(end) || (end == null && start.equals(roomEnd))) {
            var window = windowAt(start);
            window.ifPresent(place);
            return window.isPresent();
        }
        if (roomStart.equals(roomEnd)) {
            if (start.isAfter(roomStart) || (end != null && !end.isAfter(roomStart))) {
                return false;
            }
            place.accept(windows.getFirst());
            return true;
        }
        Instant first = start.isBefore(roomStart) ? roomStart : start;
        Instant last = end == null || end.isAfter(roomEnd) ? roomEnd : end;
        if (!first.isBefore(last)) {
            return false;
        }
        for (int i = windowIndex(first); i < windows.size() && windows.get(i).window.startAt().isBefore(last); i++) {
            place.accept(windows.get(i));
        }
        return true;
    }

    private static ReportTimeline.Poll pollResult(ReportInput.Poll poll) {
        var counts = new LinkedHashMap<String, Long>();
        for (var option : poll.options()) {
            String key = poll.type() == PollType.UNDERSTANDING ? option.understandingStatus().name() : option.content();
            counts.put(key, option.count());
        }
        var understanding = poll.understanding();
        return new ReportTimeline.Poll(poll.pollId(), poll.type(), PollStatus.CLOSED, poll.question(),
                poll.openedAt(), poll.closedAt(), poll.totalParticipants(), poll.respondedParticipants(),
                poll.unrespondedParticipants(), poll.responseRate(), Collections.unmodifiableMap(counts),
                List.copyOf(poll.options()), understanding == null ? null : understanding.lackCount(),
                understanding == null ? null : understanding.lackRatio(),
                understanding == null ? null : understanding.lowUnderstanding());
    }

    private void addOpinions(WindowData window, ReportInput.Analysis analysis) {
        if (analysis.status() == AiAnalysisStatus.FAILED) {
            window.opinions.add(new ReportTimeline.Opinion(analysis.analysisId(), analysis.status(), analysis.startAt(),
                    analysis.endAt(), null, null, null, List.of(), List.of(), null));
            limitations.add(analysisFailureLimitation(analysis.startAt(), analysis.endAt()));
            return;
        }
        for (var topic : analysis.topics()) {
            window.opinions.add(new ReportTimeline.Opinion(analysis.analysisId(), analysis.status(),
                    analysis.startAt(), analysis.endAt(), topic.topic(), topic.category(), topic.summary(),
                    List.of(topic.groupId()), List.copyOf(topic.messageIds()), topic.messageCount()));
        }
    }

    static String gapLimitation(SttRecordsView.Gap gap) {
        String end = gap.endedAt() == null ? "복구 시각 미확인" : KOREAN_TIME.format(gap.endedAt());
        return "%s ~ %s (한국 시간) · 발언 기록이 없어 설명·답변 여부를 확인하기 어렵습니다."
                .formatted(KOREAN_TIME.format(gap.startedAt()), end);
    }

    static String analysisFailureLimitation(Instant start, Instant end) {
        return "%s ~ %s (한국 시간) · 청중 의견 AI 분석 실패. 해당 구간의 채팅 원문은 그대로 표시합니다."
                .formatted(KOREAN_TIME.format(start), KOREAN_TIME.format(end));
    }

    private static final class WindowData {
        final ReportInput.Window window;
        final List<ReportTimeline.Segment> segments = new ArrayList<>();
        final List<SttRecordsView.Gap> gaps = new ArrayList<>();
        final List<ReportTimeline.Chat> chats = new ArrayList<>();
        final List<ReportTimeline.Poll> understandingChecks = new ArrayList<>();
        final List<ReportTimeline.Opinion> opinions = new ArrayList<>();
        final List<ReportTimeline.Poll> polls = new ArrayList<>();

        WindowData(ReportInput.Window window) {
            this.window = window;
        }

        boolean hasRecords() {
            return !segments.isEmpty() || !gaps.isEmpty() || !chats.isEmpty() || !understandingChecks.isEmpty()
                    || !opinions.isEmpty() || !polls.isEmpty();
        }

        ReportTimeline.Window snapshot() {
            return new ReportTimeline.Window(window.startAt(), window.endAt(), List.copyOf(segments), List.copyOf(gaps),
                    List.copyOf(chats), List.copyOf(understandingChecks), List.copyOf(opinions), List.copyOf(polls));
        }
    }
}
