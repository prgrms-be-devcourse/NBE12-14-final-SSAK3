package com.react.report.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.domain.TopicCategory;
import com.react.poll.dto.PollOptionResultView;
import com.react.poll.entity.PollStatus;
import com.react.poll.entity.PollType;
import com.react.stt.dto.SttRecordsView;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 저장된 기록으로 구성하는 보고서의 시간대별 상세와 전체 데이터 한계. */
public record ReportTimeline(List<Window> timeline, List<String> dataLimitations) {

    public record Window(Instant startAt, Instant endAt, List<Segment> sttSegments, List<SttRecordsView.Gap> sttGaps,
                         List<Chat> chatMessages, List<Poll> understandingChecks, List<Opinion> audienceOpinions,
                         List<Poll> polls) {}

    public record Segment(long segmentId, String transcript, Instant startAt, Instant endAt) {}

    public record Chat(long messageId, String text, Instant createdAt) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Poll(long pollId, PollType type, PollStatus status, String question, Instant openedAt, Instant closedAt,
                       long totalParticipants, long respondedParticipants, long unrespondedParticipants,
                       double responseRate, Map<String, Long> counts, List<PollOptionResultView> options,
                       Long lackCount, Double lackRatio, Boolean lowUnderstanding) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Opinion(long analysisId, AiAnalysisStatus status, Instant startAt, Instant endAt,
                          String topic, TopicCategory category, String summary, List<Long> topicGroupIds,
                          List<Long> sourceMessageIds, Integer messageCount) {}
}
