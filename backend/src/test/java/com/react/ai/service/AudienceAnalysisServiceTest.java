package com.react.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.dto.AudienceMessage;
import com.react.ai.dto.AudienceAnalysisLlmResponse;
import com.react.ai.repository.AiAnalysisRepository;
import com.react.ai.repository.AudienceMessageRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class AudienceAnalysisServiceTest {
    private final AiAnalysisRepository analyses = mock(AiAnalysisRepository.class);
    private final AudienceMessageRepository messages = mock(AudienceMessageRepository.class);
    private final AudienceAnalysisLlmClient llm = mock(AudienceAnalysisLlmClient.class);
    private final AudienceAnalysisService service = new AudienceAnalysisService(
            analyses, messages, llm, new AiAnalysisJsonParser(new ObjectMapper()));
    private final LocalDateTime start = LocalDateTime.of(2026, 11, 17, 10, 0);
    private final LocalDateTime end = start.plusSeconds(30);

    @Test
    void 빈_구간은_LLM_호출_없이_완료로_기록한다() {
        acquireCreated(1L);
        when(messages.findAudienceMessages(10L, start, end)).thenReturn(List.of());

        var result = service.process(10L, start, end);

        assertThat(result.status()).isEqualTo(AiAnalysisStatus.COMPLETED);
        assertThat(result.topicGroupCount()).isZero();
        verify(analyses).completeEmpty(1L);
        verifyNoInteractions(llm);
    }

    @Test
    void 후보에_없는_메시지_ID를_참조하면_실패한다() {
        acquireCreated(1L);
        when(messages.findAudienceMessages(10L, start, end))
                .thenReturn(List.of(new AudienceMessage(100L, 10L, "질문", start)));
        when(llm.analyze(any())).thenReturn(response("""
                {"topics":[{"topic":"주제","summary":"요약","category":"QUESTION","sourceMessageIds":[999]}]}
                """));

        var result = service.process(10L, start, end);

        assertThat(result.status()).isEqualTo(AiAnalysisStatus.FAILED);
        verify(analyses).markFailed(eq(1L), any());
        verify(analyses, never()).saveCompleted(anyLong(), any(), any());
    }

    @Test
    void 이미_생성된_구간은_다시_호출하지_않는다() {
        var analysis = new AiAnalysisRepository.Analysis(1L, 10L, start, end, AiAnalysisStatus.COMPLETED);
        when(analyses.acquire(10L, start, end)).thenReturn(new AiAnalysisRepository.AcquiredAnalysis(analysis, false));

        var result = service.process(10L, start, end);

        assertThat(result.created()).isFalse();
        assertThat(result.status()).isEqualTo(AiAnalysisStatus.COMPLETED);
        verifyNoInteractions(messages, llm);
    }

    @Test
    void 하나의_메시지를_여러_주제에_연결하면_실패한다() {
        acquireCreated(1L);
        when(messages.findAudienceMessages(10L, start, end))
                .thenReturn(List.of(new AudienceMessage(100L, 10L, "질문", start)));
        when(llm.analyze(any())).thenReturn(response("""
                {"topics":[
                  {"topic":"주제1","summary":"요약1","category":"QUESTION","sourceMessageIds":[100]},
                  {"topic":"주제2","summary":"요약2","category":"OPINION","sourceMessageIds":[100]}
                ]}
                """));

        var result = service.process(10L, start, end);

        assertThat(result.status()).isEqualTo(AiAnalysisStatus.FAILED);
        verify(analyses).markFailed(eq(1L), any());
    }

    private void acquireCreated(long analysisId) {
        var analysis = new AiAnalysisRepository.Analysis(analysisId, 10L, start, end, AiAnalysisStatus.PENDING);
        when(analyses.acquire(10L, start, end)).thenReturn(new AiAnalysisRepository.AcquiredAnalysis(analysis, true));
        when(analyses.markProcessing(analysisId)).thenReturn(true);
        when(analyses.completeEmpty(analysisId)).thenReturn(true);
        when(analyses.markFailed(anyLong(), any())).thenReturn(true);
    }

    private AudienceAnalysisLlmResponse response(String content) {
        return new AudienceAnalysisLlmResponse(content, "fake-model");
    }
}
