package com.react.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.domain.AiLlmCallStatus;
import com.react.ai.config.AudienceAnalysisCostProperties;
import com.react.ai.config.AudienceAnalysisRetryProperties;
import com.react.ai.dto.AiLlmCallUsage;
import com.react.ai.exception.GeminiAudienceAnalysisErrorCode;
import com.react.ai.exception.GeminiAudienceAnalysisException;
import com.react.ai.dto.AudienceMessage;
import com.react.ai.dto.AudienceAnalysisLlmResponse;
import com.react.ai.repository.AiAnalysisRepository;
import com.react.ai.repository.AiLlmCallUsageRepository;
import com.react.ai.repository.AudienceMessageRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class AudienceAnalysisServiceTest {
    private final AiAnalysisRepository analyses = mock(AiAnalysisRepository.class);
    private final AudienceMessageRepository messages = mock(AudienceMessageRepository.class);
    private final AudienceAnalysisLlmClient llm = mock(AudienceAnalysisLlmClient.class);
    private final AiLlmCallUsageRepository usages = mock(AiLlmCallUsageRepository.class);
    private final AiAnalysisCompletionService completions = mock(AiAnalysisCompletionService.class);
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
    void 새_완료_분석은_완료_저장_서비스를_통해_이벤트_발행_대상으로_전달한다() {
        acquireCreated(1L);
        when(messages.findAudienceMessages(10L, start, end)).thenReturn(List.of(message()));
        when(llm.analyze(any())).thenReturn(response("""
                {"topics":[{"topic":"주제","summary":"요약","category":"QUESTION","sourceMessageIds":[100]}]}
                """));

        var result = serviceWithCompletion().process(10L, start, end);

        assertThat(result.status()).isEqualTo(AiAnalysisStatus.COMPLETED);
        verify(completions).completeWithTopics(eq(10L), eq(1L), any(), eq("fake-model"));
    }

    @Test
    void 빈_구간도_완료_저장_서비스를_통해_이벤트_발행_대상으로_전달한다() {
        acquireCreated(1L);
        when(messages.findAudienceMessages(10L, start, end)).thenReturn(List.of());
        when(completions.completeEmpty(10L, 1L)).thenReturn(true);

        var result = serviceWithCompletion().process(10L, start, end);

        assertThat(result.status()).isEqualTo(AiAnalysisStatus.COMPLETED);
        verify(completions).completeEmpty(10L, 1L);
        verifyNoInteractions(llm);
    }

    @Test
    void 기존_분석_구간은_완료_저장_서비스를_다시_호출하지_않는다() {
        var analysis = new AiAnalysisRepository.Analysis(1L, 10L, start, end, AiAnalysisStatus.COMPLETED);
        when(analyses.acquire(10L, start, end)).thenReturn(new AiAnalysisRepository.AcquiredAnalysis(analysis, false));

        serviceWithCompletion().process(10L, start, end);

        verifyNoInteractions(completions);
    }

    @Test
    void FAILED_분석은_완료_저장_서비스를_호출하지_않는다() {
        acquireCreated(1L);
        when(messages.findAudienceMessages(10L, start, end))
                .thenReturn(List.of(new AudienceMessage(100L, 10L, "질문", start)));
        when(llm.analyze(any())).thenReturn(response("{topics:[]}"));

        var result = serviceWithCompletion().process(10L, start, end);

        assertThat(result.status()).isEqualTo(AiAnalysisStatus.FAILED);
        verifyNoInteractions(completions);
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

    @Test
    void 요청_제한_후_한번_재시도해_성공하면_결과를_한번만_저장한다() {
        retryableFailureThenSuccess(GeminiAudienceAnalysisErrorCode.RATE_LIMIT);
    }

    @Test
    void Gemini_5xx_후_한번_재시도해_성공한다() {
        retryableFailureThenSuccess(GeminiAudienceAnalysisErrorCode.HTTP_5XX);
    }

    @Test
    void 타임아웃_후_한번_재시도해_성공한다() {
        retryableFailureThenSuccess(GeminiAudienceAnalysisErrorCode.TIMEOUT);
    }

    @Test
    void 재시도_한도를_초과하면_오류_코드와_함께_FAILED로_기록한다() {
        acquireCreated(1L);
        when(messages.findAudienceMessages(10L, start, end)).thenReturn(List.of(message()));
        when(llm.analyze(any())).thenThrow(retryable(GeminiAudienceAnalysisErrorCode.HTTP_5XX));

        var result = service.process(10L, start, end);

        assertThat(result.status()).isEqualTo(AiAnalysisStatus.FAILED);
        verify(llm, times(2)).analyze(any());
        verify(analyses).markFailed(1L, "HTTP_5XX");
        verify(analyses, never()).saveCompleted(anyLong(), any(), any());
    }

    @Test
    void 재시도_직전에_PROCESSING이_아니면_추가_Gemini_호출을_중단하고_기존_상태를_덮어쓰지_않는다() {
        acquireCreated(1L);
        when(messages.findAudienceMessages(10L, start, end)).thenReturn(List.of(message()));
        when(llm.analyze(any())).thenThrow(retryable(GeminiAudienceAnalysisErrorCode.RATE_LIMIT));
        when(analyses.isProcessing(1L)).thenReturn(false);
        when(analyses.markFailed(1L, "AI_ANALYSIS_PROCESSING_FAILED")).thenReturn(false);

        assertThatThrownBy(() -> service.process(10L, start, end))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("분석 실패 상태로 변경할 수 없습니다.");

        verify(llm, times(1)).analyze(any());
        verify(analyses).isProcessing(1L);
        verify(analyses).markFailed(1L, "AI_ANALYSIS_PROCESSING_FAILED");
    }

    @Test
    void 인증_오류는_재시도하지_않고_AUTH로_기록한다() {
        acquireCreated(1L);
        when(messages.findAudienceMessages(10L, start, end)).thenReturn(List.of(message()));
        when(llm.analyze(any())).thenThrow(nonRetryable(GeminiAudienceAnalysisErrorCode.AUTH));

        var result = service.process(10L, start, end);

        assertThat(result.status()).isEqualTo(AiAnalysisStatus.FAILED);
        verify(llm).analyze(any());
        verify(analyses).markFailed(1L, "AUTH");
    }

    @Test
    void 잘못된_LLM_JSON은_재시도하지_않고_INVALID_LLM_RESPONSE로_기록한다() {
        acquireCreated(1L);
        when(messages.findAudienceMessages(10L, start, end)).thenReturn(List.of(message()));
        when(llm.analyze(any())).thenReturn(response("{topics:[]}"));

        var result = service.process(10L, start, end);

        assertThat(result.status()).isEqualTo(AiAnalysisStatus.FAILED);
        verify(llm).analyze(any());
        verify(analyses).markFailed(1L, "INVALID_LLM_RESPONSE");
    }

    @Test
    void 재시도_호출은_시도별로_실패와_성공_이력을_각각_저장한다() {
        acquireCreated(1L);
        when(messages.findAudienceMessages(10L, start, end)).thenReturn(List.of(message()));
        when(llm.modelName()).thenReturn("gemini-test");
        when(llm.analyze(any()))
                .thenThrow(retryable(GeminiAudienceAnalysisErrorCode.RATE_LIMIT))
                .thenReturn(new AudienceAnalysisLlmResponse(
                        "{\"topics\":[{\"topic\":\"주제\",\"summary\":\"요약\",\"category\":\"QUESTION\",\"sourceMessageIds\":[100]}]}",
                        "gemini-test", new AudienceAnalysisLlmResponse.Usage(120L, 80L, 200L)));

        observedService().process(10L, start, end);

        var captor = org.mockito.ArgumentCaptor.forClass(AiLlmCallUsage.class);
        verify(usages, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(AiLlmCallUsage::attemptNumber).containsExactly(1, 2);
        assertThat(captor.getAllValues()).extracting(AiLlmCallUsage::callStatus)
                .containsExactly(AiLlmCallStatus.FAILED, AiLlmCallStatus.SUCCESS);
        assertThat(captor.getAllValues().getFirst().errorCode()).isEqualTo("RATE_LIMIT");
        assertThat(captor.getAllValues().getFirst().promptTokenCount()).isNull();
        assertThat(captor.getAllValues().get(1).estimatedCostMicrousd()).isEqualTo(150L);
    }

    @Test
    void 호출_이력_저장_실패는_분석_완료를_막지_않는다() {
        acquireCreated(1L);
        when(messages.findAudienceMessages(10L, start, end)).thenReturn(List.of(message()));
        when(llm.analyze(any())).thenReturn(response("""
                {"topics":[{"topic":"주제","summary":"요약","category":"QUESTION","sourceMessageIds":[100]}]}
                """));
        doThrow(new IllegalStateException("usage database failure")).when(usages).save(any());

        var result = observedService().process(10L, start, end);

        assertThat(result.status()).isEqualTo(AiAnalysisStatus.COMPLETED);
        verify(analyses).saveCompleted(eq(1L), any(), eq("fake-model"));
    }

    @Test
    void usageMetadata가_있는_비정상_Gemini_응답은_FAILED_이력에_토큰을_기록한다() {
        acquireCreated(1L);
        when(messages.findAudienceMessages(10L, start, end)).thenReturn(List.of(message()));
        when(llm.modelName()).thenReturn("gemini-test");
        when(llm.analyze(any())).thenThrow(new GeminiAudienceAnalysisException(
                GeminiAudienceAnalysisErrorCode.INVALID_RESPONSE, false, null,
                new AudienceAnalysisLlmResponse.Usage(120L, 80L, 200L), "invalid response"));

        var result = observedService().process(10L, start, end);

        assertThat(result.status()).isEqualTo(AiAnalysisStatus.FAILED);
        verify(llm).analyze(any());
        var captor = org.mockito.ArgumentCaptor.forClass(AiLlmCallUsage.class);
        verify(usages).save(captor.capture());
        assertThat(captor.getValue()).extracting(
                AiLlmCallUsage::callStatus, AiLlmCallUsage::errorCode,
                AiLlmCallUsage::promptTokenCount, AiLlmCallUsage::outputTokenCount,
                AiLlmCallUsage::totalTokenCount, AiLlmCallUsage::estimatedCostMicrousd)
                .containsExactly(AiLlmCallStatus.FAILED, "INVALID_RESPONSE", 120L, 80L, 200L, 150L);
        verify(analyses).markFailed(1L, "INVALID_RESPONSE");
    }

    private void retryableFailureThenSuccess(GeminiAudienceAnalysisErrorCode errorCode) {
        acquireCreated(1L);
        when(messages.findAudienceMessages(10L, start, end)).thenReturn(List.of(message()));
        when(llm.analyze(any()))
                .thenThrow(retryable(errorCode))
                .thenReturn(response("""
                        {"topics":[{"topic":"주제","summary":"요약","category":"QUESTION","sourceMessageIds":[100]}]}
                        """));

        var result = service.process(10L, start, end);

        assertThat(result.status()).isEqualTo(AiAnalysisStatus.COMPLETED);
        verify(llm, times(2)).analyze(any());
        verify(analyses, times(1)).saveCompleted(eq(1L), any(), eq("fake-model"));
        verify(analyses, never()).markFailed(anyLong(), any());
    }

    private GeminiAudienceAnalysisException retryable(GeminiAudienceAnalysisErrorCode errorCode) {
        return new GeminiAudienceAnalysisException(errorCode, true, Duration.ZERO, "retryable failure");
    }

    private GeminiAudienceAnalysisException nonRetryable(GeminiAudienceAnalysisErrorCode errorCode) {
        return new GeminiAudienceAnalysisException(errorCode, false, null, "non-retryable failure");
    }

    private AudienceMessage message() {
        return new AudienceMessage(100L, 10L, "질문", start);
    }

    private void acquireCreated(long analysisId) {
        var analysis = new AiAnalysisRepository.Analysis(analysisId, 10L, start, end, AiAnalysisStatus.PENDING);
        when(analyses.acquire(10L, start, end)).thenReturn(new AiAnalysisRepository.AcquiredAnalysis(analysis, true));
        when(analyses.markProcessing(analysisId)).thenReturn(true);
        when(analyses.isProcessing(analysisId)).thenReturn(true);
        when(analyses.completeEmpty(analysisId)).thenReturn(true);
        when(analyses.markFailed(anyLong(), any())).thenReturn(true);
    }

    private AudienceAnalysisLlmResponse response(String content) {
        return new AudienceAnalysisLlmResponse(content, "fake-model");
    }

    private AudienceAnalysisService observedService() {
        return new AudienceAnalysisService(analyses, messages, llm, new AiAnalysisJsonParser(new ObjectMapper()),
                new AudienceAnalysisRetryProperties(1, Duration.ZERO, Duration.ofSeconds(5)), usages,
                new AudienceAnalysisCostProperties("0.25", "1.50"));
    }

    private AudienceAnalysisService serviceWithCompletion() {
        return new AudienceAnalysisService(analyses, messages, llm, new AiAnalysisJsonParser(new ObjectMapper()),
                new AudienceAnalysisRetryProperties(1, Duration.ZERO, Duration.ofSeconds(5)), usages,
                new AudienceAnalysisCostProperties("0.25", "1.50"), completions);
    }
}
