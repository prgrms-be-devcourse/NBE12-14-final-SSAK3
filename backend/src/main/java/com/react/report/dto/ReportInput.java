package com.react.report.dto;

import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.domain.TopicCategory;
import com.react.poll.dto.PollOptionResultView;
import com.react.poll.entity.PollType;
import com.react.stt.dto.SttRecordsView;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 종료된 강연 한 건의 서버 내부 입력. AI 분석은 원문을 복사하지 않고 메시지 ID로 연결한다. */
public record ReportInput(long roomId, Instant startedAt, Instant endedAt, SttRecordsView stt,
                          List<Chat> chats, List<Analysis> analyses, List<Poll> polls) {

    public record Window(Instant startAt, Instant endAt) {
        public static Window forPoint(Instant time, Instant roomStart, Instant roomEnd) {
            long seconds = Duration.between(roomStart, time).getSeconds();
            // 종료 정각이 분 경계여도 빈 다음 구간을 만들지 않는다.
            if (time.equals(roomEnd) && seconds > 0) {
                seconds--;
            }
            Instant start = roomStart.plusSeconds(seconds / 60 * 60);
            Instant end = start.plusSeconds(60);
            return new Window(start, end.isAfter(roomEnd) ? roomEnd : end);
        }
    }

    public record Chat(long messageId, String participantId, String text, Instant receivedAt, Window window) {}

    public record Analysis(long analysisId, Instant startAt, Instant endAt, AiAnalysisStatus status,
                           List<Topic> topics) {}

    public record Topic(long groupId, String topic, TopicCategory category, String summary, List<Long> messageIds) {
        public int messageCount() {
            return messageIds.size();
        }
    }

    public record Poll(long pollId, PollType type, String question, Instant openedAt, Instant closedAt, Window window,
                       long totalParticipants, long respondedParticipants, long unrespondedParticipants,
                       double responseRate, List<PollOptionResultView> options, Understanding understanding) {}

    public record Understanding(long lackCount, double lackRatio, boolean lowUnderstanding) {}
}
