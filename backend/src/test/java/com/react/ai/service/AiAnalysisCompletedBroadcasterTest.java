package com.react.ai.service;

import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.domain.TopicCategory;
import com.react.ai.dto.AiAnalysisCompletedEvent;
import com.react.ai.event.AiAnalysisCompletedDomainEvent;
import com.react.ai.repository.AiAnalysisQueryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AiAnalysisCompletedBroadcasterTest {
    private final AiAnalysisQueryRepository queries = mock(AiAnalysisQueryRepository.class);
    private final SimpMessagingTemplate broker = mock(SimpMessagingTemplate.class);
    private final AiAnalysisCompletedBroadcaster broadcaster = new AiAnalysisCompletedBroadcaster(queries, broker);

    @Test
    void 커밋된_완료_분석을_명세_payload로_HOST_전용_채널에_방송한다() {
        LocalDateTime completedAt = LocalDateTime.of(2026, 11, 23, 10, 0, 8);
        var analysis = new AiAnalysisQueryRepository.AnalysisRow(20L,
                completedAt.minusSeconds(30), completedAt.minusSeconds(1), AiAnalysisStatus.COMPLETED, completedAt);
        var group = new AiAnalysisQueryRepository.TopicGroupRow(30L, 20L, "Redis 사용 이유",
                TopicCategory.MIXED, "도입 이유와 사례에 대한 의견입니다.", 2L);
        when(queries.findCompletedAnalysis(10L, 20L)).thenReturn(Optional.of(analysis));
        when(queries.findTopicGroups(List.of(20L))).thenReturn(List.of(group));

        broadcaster.broadcast(new AiAnalysisCompletedDomainEvent(10L, 20L));

        var payload = org.mockito.ArgumentCaptor.forClass(AiAnalysisCompletedEvent.class);
        verify(broker).convertAndSend(eq("/topic/rooms/10/ai"), payload.capture());
        assertThat(payload.getValue().type()).isEqualTo("AI_ANALYSIS_COMPLETED");
        assertThat(payload.getValue().data().analysisId()).isEqualTo(20L);
        assertThat(payload.getValue().data().roomId()).isEqualTo(10L);
        assertThat(payload.getValue().data().completedAt().toString()).isEqualTo("2026-11-23T01:00:08Z");
        assertThat(payload.getValue().data().topicGroups()).containsExactly(
                new AiAnalysisCompletedEvent.TopicGroup(30L, "Redis 사용 이유", TopicCategory.MIXED,
                        "도입 이유와 사례에 대한 의견입니다.", 2L));
    }

    @Test
    void 빈_구간은_빈_주제_목록으로_완료_이벤트를_방송한다() {
        LocalDateTime completedAt = LocalDateTime.of(2026, 11, 23, 10, 0, 8);
        when(queries.findCompletedAnalysis(10L, 20L)).thenReturn(Optional.of(
                new AiAnalysisQueryRepository.AnalysisRow(20L, completedAt.minusSeconds(30), completedAt,
                        AiAnalysisStatus.COMPLETED, completedAt)));
        when(queries.findTopicGroups(List.of(20L))).thenReturn(List.of());

        broadcaster.broadcast(new AiAnalysisCompletedDomainEvent(10L, 20L));

        var payload = org.mockito.ArgumentCaptor.forClass(AiAnalysisCompletedEvent.class);
        verify(broker).convertAndSend(eq("/topic/rooms/10/ai"), payload.capture());
        assertThat(payload.getValue().data().topicGroups()).isEmpty();
    }

    @Test
    void 완료_행이_없거나_방송이_실패해도_예외를_전파하지_않는다() {
        when(queries.findCompletedAnalysis(10L, 20L)).thenReturn(Optional.empty());

        assertThatCode(() -> broadcaster.broadcast(new AiAnalysisCompletedDomainEvent(10L, 20L)))
                .doesNotThrowAnyException();
        verifyNoInteractions(broker);

        LocalDateTime completedAt = LocalDateTime.of(2026, 11, 23, 10, 0, 8);
        when(queries.findCompletedAnalysis(10L, 20L)).thenReturn(Optional.of(
                new AiAnalysisQueryRepository.AnalysisRow(20L, completedAt.minusSeconds(30), completedAt,
                        AiAnalysisStatus.COMPLETED, completedAt)));
        when(queries.findTopicGroups(List.of(20L))).thenReturn(List.of());
        doThrow(new IllegalStateException("broker failure")).when(broker)
                .convertAndSend(any(String.class), org.mockito.ArgumentMatchers.<Object>any());

        assertThatCode(() -> broadcaster.broadcast(new AiAnalysisCompletedDomainEvent(10L, 20L)))
                .doesNotThrowAnyException();
    }
}
