package com.react.ai.service;

import com.react.ai.domain.TopicCategory;
import com.react.ai.dto.AiAnalysisResult;
import com.react.ai.dto.AiTopicResult;
import com.react.ai.event.AiAnalysisCompletedDomainEvent;
import com.react.ai.repository.AiAnalysisRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AiAnalysisCompletionServiceTest {
    private final AiAnalysisRepository analyses = mock(AiAnalysisRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final AiAnalysisCompletionService service = new AiAnalysisCompletionService(analyses, events);

    @Test
    void 주제_분석_완료를_저장한_뒤_내부_완료_이벤트를_등록한다() {
        AiAnalysisResult result = new AiAnalysisResult(List.of(
                new AiTopicResult("주제", "요약", TopicCategory.QUESTION, List.of(100L))));

        service.completeWithTopics(10L, 20L, result, "gemini-test");

        var order = inOrder(analyses, events);
        order.verify(analyses).saveCompleted(20L, result, "gemini-test");
        order.verify(events).publishEvent(new AiAnalysisCompletedDomainEvent(10L, 20L));
    }

    @Test
    void 빈_구간이_완료된_경우에만_내부_완료_이벤트를_등록한다() {
        when(analyses.completeEmpty(20L)).thenReturn(true);

        assertThatCode(() -> service.completeEmpty(10L, 20L)).doesNotThrowAnyException();

        verify(events).publishEvent(new AiAnalysisCompletedDomainEvent(10L, 20L));
    }

    @Test
    void 빈_구간_완료가_실패하면_완료_이벤트를_등록하지_않는다() {
        when(analyses.completeEmpty(20L)).thenReturn(false);

        service.completeEmpty(10L, 20L);

        verify(events, never()).publishEvent(any());
    }

    @Test
    void 내부_이벤트_등록_실패는_이미_저장된_완료_처리를_실패시키지_않는다() {
        doThrow(new IllegalStateException("listener failure")).when(events).publishEvent(any());
        AiAnalysisResult result = new AiAnalysisResult(List.of());

        assertThatCode(() -> service.completeWithTopics(10L, 20L, result, "gemini-test"))
                .doesNotThrowAnyException();

        verify(analyses).saveCompleted(eq(20L), eq(result), eq("gemini-test"));
    }
}
