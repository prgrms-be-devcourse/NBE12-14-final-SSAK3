package com.react.report.service;

import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.repository.AiAnalysisQueryRepository;
import com.react.ai.repository.AiAnalysisRepository;
import com.react.ai.service.AiAnalysisWindowCalculator;
import com.react.ai.service.AiAnalysisWindowCalculator.AnalysisWindow;
import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.poll.dto.PollOptionResultView;
import com.react.poll.entity.PollStatus;
import com.react.poll.entity.PollType;
import com.react.poll.entity.UnderstandingStatus;
import com.react.poll.service.PollResultMath;
import com.react.report.dto.ReportInput;
import com.react.report.dto.ReportTimeline;
import com.react.report.repository.ReportInputRepository;
import com.react.stt.service.SttSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportInputService {
    private final ReportInputRepository inputs;
    private final ReportRoomAccess rooms;
    private final SttSessionService stt;
    private final AiAnalysisRepository analyses;
    private final AiAnalysisQueryRepository topics;
    private final AiAnalysisWindowCalculator windows = new AiAnalysisWindowCalculator();

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportTimeline collectTimeline(long roomId, String hostEmail) {
        return ReportTimelineAssembler.assemble(collect(roomId, hostEmail));
    }

    /** hostEmail은 인증된 강연자 이메일이다. 모든 조회가 같은 DB 스냅샷을 읽는다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportInput collect(long roomId, String hostEmail) {
        var room = rooms.requireOwnedEndedRoom(roomId, hostEmail);
        if (room.startedAt() == null || room.endedAt() == null || room.endedAt().isBefore(room.startedAt())) {
            throw notReady();
        }

        Instant start = instant(room.startedAt());
        Instant end = instant(room.endedAt());
        var aliases = new HashMap<Long, String>();
        var chats = inputs.findChats(roomId, room.startedAt(), room.endedAt()).stream().map(row -> {
            String alias = aliases.computeIfAbsent(row.participantId(), id -> "p" + (aliases.size() + 1));
            Instant time = instant(row.receivedAt());
            return new ReportInput.Chat(row.messageId(), alias, row.content(), time,
                    ReportInput.Window.forPoint(time, start, end));
        }).toList();
        var chatIds = chats.stream().map(ReportInput.Chat::messageId).collect(Collectors.toSet());
        return new ReportInput(roomId, start, end, stt.records(roomId, hostEmail, null, null), chats,
                collectAnalyses(roomId, room, chatIds), collectPolls(roomId, start, end));
    }

    private List<ReportInput.Analysis> collectAnalyses(long roomId, ReportInputRepository.Room room, Set<Long> chatIds) {
        var byWindow = analyses.findWithin(roomId, room.startedAt(), room.endedAt()).stream()
                .collect(Collectors.toMap(a -> new AnalysisWindow(a.windowStartAt(), a.windowEndAt()), Function.identity()));
        var terminal = new ArrayList<AiAnalysisRepository.Analysis>();
        for (var window : windows.finalizationWindows(room.startedAt(), room.endedAt())) {
            var row = byWindow.get(window);
            if (row == null || (row.status() != AiAnalysisStatus.COMPLETED && row.status() != AiAnalysisStatus.FAILED)) {
                throw notReady();
            }
            terminal.add(row);
        }
        var completed = terminal.stream().filter(a -> a.status() == AiAnalysisStatus.COMPLETED)
                .map(AiAnalysisRepository.Analysis::id).toList();
        var groups = topics.findTopicGroups(completed);
        var links = topics.findTopicGroupMessageIds(groups.stream()
                .map(AiAnalysisQueryRepository.TopicGroupRow::topicGroupId).toList()).stream()
                .collect(Collectors.groupingBy(AiAnalysisQueryRepository.TopicGroupMessageIdRow::topicGroupId,
                        Collectors.mapping(AiAnalysisQueryRepository.TopicGroupMessageIdRow::chatMessageId, Collectors.toList())));

        var byAnalysis = new HashMap<Long, List<ReportInput.Topic>>();
        for (var group : groups) {
            var ids = links.getOrDefault(group.topicGroupId(), List.of());
            if (!chatIds.containsAll(ids)) {
                // 잘못 연결된 다른 방·강연자의 메시지는 요약의 근거로 내보내지 않는다.
                throw notReady();
            }
            byAnalysis.computeIfAbsent(group.analysisId(), id -> new ArrayList<>())
                    .add(new ReportInput.Topic(group.topicGroupId(), group.topic(), group.category(), group.summary(), ids));
        }
        return terminal.stream().map(a -> new ReportInput.Analysis(a.id(), instant(a.windowStartAt()),
                instant(a.windowEndAt()), a.status(), byAnalysis.getOrDefault(a.id(), List.of()))).toList();
    }

    private List<ReportInput.Poll> collectPolls(long roomId, Instant start, Instant end) {
        var options = inputs.findOptionCounts(roomId).stream()
                .collect(Collectors.groupingBy(ReportInputRepository.Option::pollId));
        return inputs.findPolls(roomId).stream().map(poll -> {
            if (poll.status() != PollStatus.CLOSED || poll.closedAt() == null || poll.totalParticipants() == null) {
                throw notReady();
            }
            var rows = options.getOrDefault(poll.pollId(), List.of());
            long responded = rows.stream().mapToLong(ReportInputRepository.Option::responseCount).sum();
            long total = poll.totalParticipants();
            if (responded > total) {
                throw notReady();
            }
            var results = rows.stream().map(o -> new PollOptionResultView(o.optionId(), o.content(),
                    o.understandingStatus(), o.responseCount(), PollResultMath.percentage(o.responseCount(), responded),
                    PollResultMath.percentage(o.responseCount(), total))).toList();
            Instant openedAt = instant(poll.openedAt());
            return new ReportInput.Poll(poll.pollId(), poll.type(), poll.question(), openedAt, instant(poll.closedAt()),
                    ReportInput.Window.forPoint(openedAt, start, end), total, responded,
                    PollResultMath.unresponded(total, responded), PollResultMath.percentage(responded, total),
                    results, understanding(poll.type(), results, responded));
        }).toList();
    }

    private static ReportInput.Understanding understanding(PollType type, List<PollOptionResultView> options, long responded) {
        if (type != PollType.UNDERSTANDING) {
            return null;
        }
        long lack = options.stream().filter(o -> o.understandingStatus() == UnderstandingStatus.CONFUSED
                        || o.understandingStatus() == UnderstandingStatus.NOT_UNDERSTOOD)
                .mapToLong(PollOptionResultView::count).sum();
        double ratio = PollResultMath.percentage(lack, responded);
        return new ReportInput.Understanding(lack, ratio, lack >= 3 && ratio >= 40.0);
    }

    private static BusinessException notReady() {
        return new BusinessException(ErrorCode.REPORT_INPUT_NOT_READY);
    }

    private static Instant instant(LocalDateTime stored) {
        return stored.toInstant(ZoneOffset.ofHours(9));
    }
}
