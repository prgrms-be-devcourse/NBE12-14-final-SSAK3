package com.react.report.service;

import com.react.ai.domain.AiAnalysisStatus;
import com.react.report.dto.ReportInput.Window;
import com.react.report.dto.ReportTimeline;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 시간대와 투표의 실제 개설·마감 범위에 영향을 준 누락·실패만 요약에 연결한다. */
final class ReportOverviewLimitations {
    private final Map<Window, Set<String>> notesByWindow = new HashMap<>();
    private final Map<String, Interval> intervals = new LinkedHashMap<>();

    ReportOverviewLimitations(ReportTimeline timeline) {
        for (var row : timeline.timeline()) {
            var notes = new LinkedHashSet<String>();
            for (var gap : row.sttGaps()) {
                String note = ReportTimelineAssembler.gapLimitation(gap);
                notes.add(note);
                intervals.put(note, new Interval(gap.startedAt(), gap.endedAt()));
            }
            for (var analysis : row.audienceOpinions()) {
                if (analysis.status() == AiAnalysisStatus.FAILED) {
                    String note = ReportTimelineAssembler.analysisFailureLimitation(analysis.startAt(), analysis.endAt());
                    notes.add(note);
                    intervals.put(note, new Interval(analysis.startAt(), analysis.endAt()));
                }
            }
            if (!notes.isEmpty()) notesByWindow.put(new Window(row.startAt(), row.endAt()), notes);
        }
    }

    Set<String> forEvidence(Collection<Window> windows, Collection<ReportTimeline.Poll> polls) {
        var notes = new LinkedHashSet<String>();
        for (var window : windows) {
            notes.addAll(notesByWindow.getOrDefault(window, Set.of()));
        }
        // 투표 결과는 연 시간대에만 놓이지만, 실제 응답을 받은 기간은 다음 시간대까지 이어질 수 있다.
        for (var poll : polls) {
            intervals.forEach((note, range) -> {
                if (range.overlaps(poll.openedAt(), poll.closedAt())) notes.add(note);
            });
        }
        return notes;
    }

    private record Interval(Instant start, Instant end) {
        boolean overlaps(Instant openedAt, Instant closedAt) {
            if (start.equals(end)) return !start.isBefore(openedAt) && !start.isAfter(closedAt);
            return start.isBefore(closedAt) && (end == null || end.isAfter(openedAt));
        }
    }
}
