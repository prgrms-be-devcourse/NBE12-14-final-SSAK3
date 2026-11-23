package com.react.report.service;

import com.react.poll.entity.PollStatus;
import com.react.report.dto.ReportInput;
import com.react.report.dto.ReportTimeline;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 한 방에서 인용할 수 있는 기록. 여러 분에 걸친 발언은 원문 하나에 관련 구간을 모두 연결한다. */
final class ReportEvidenceCatalog {
    record Ref(String type, long id) {}
    record Source(Ref ref, Object data, List<ReportInput.Window> relatedWindows) {}

    private final Map<Ref, Source> records = new LinkedHashMap<>();
    private final Set<ReportInput.Window> windows = new LinkedHashSet<>();

    static ReportEvidenceCatalog from(ReportTimeline timeline) {
        var catalog = new ReportEvidenceCatalog();
        for (var row : timeline.timeline()) {
            var window = new ReportInput.Window(row.startAt(), row.endAt());
            catalog.windows.add(window);
            for (var segment : row.sttSegments()) {
                catalog.add("STT_SEGMENT", segment.segmentId(), segment, window);
            }
            for (var chat : row.chatMessages()) {
                catalog.add("CHAT_MESSAGE", chat.messageId(), chat, window);
            }
            catalog.addPolls(row.understandingChecks(), window);
            catalog.addPolls(row.polls(), window);
        }
        return catalog;
    }

    private void addPolls(List<ReportTimeline.Poll> polls, ReportInput.Window window) {
        for (var poll : polls) {
            if (poll.status() == PollStatus.CLOSED && poll.closedAt() != null && poll.respondedParticipants() > 0) {
                add("POLL_RESULT", poll.pollId(), poll, window);
            }
        }
    }

    private void add(String type, long id, Object data, ReportInput.Window window) {
        var key = new Ref(type, id);
        var source = records.computeIfAbsent(key, ref -> new Source(ref, data, new ArrayList<>()));
        source.relatedWindows().add(window);
    }

    Source find(String type, long id) {
        return records.get(new Ref(type, id));
    }

    Collection<Source> sources() {
        return Collections.unmodifiableCollection(records.values());
    }

    Set<ReportInput.Window> windows() {
        return Collections.unmodifiableSet(windows);
    }

    boolean hasAudience() {
        return records.keySet().stream().anyMatch(ref -> !ref.type().equals("STT_SEGMENT"));
    }
}
