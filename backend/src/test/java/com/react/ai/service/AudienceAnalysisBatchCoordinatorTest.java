package com.react.ai.service;

import com.react.ai.config.AiAnalysisSchedulerProperties;
import com.react.ai.config.GeminiAudienceAnalysisProperties;
import com.react.ai.domain.AiAnalysisBatchStatus;
import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.domain.TopicCategory;
import com.react.ai.dto.AiAnalysisResult;
import com.react.ai.dto.AiTopicResult;
import com.react.ai.dto.AudienceAnalysisBatch;
import com.react.ai.dto.AudienceAnalysisBatchManifest;
import com.react.ai.dto.AudienceAnalysisInputMessage;
import com.react.ai.dto.PreprocessedAudienceMessages;
import com.react.ai.repository.AiAnalysisBatchRepository;
import com.react.ai.repository.AiAnalysisRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AudienceAnalysisBatchCoordinatorTest {
    private final AiAnalysisRepository analyses = mock(AiAnalysisRepository.class);
    private final AiAnalysisBatchRepository batches = mock(AiAnalysisBatchRepository.class);
    private final AudienceAnalysisBatchSplitter splitter = mock(AudienceAnalysisBatchSplitter.class);
    private final AudienceAnalysisBatchService batchService = mock(AudienceAnalysisBatchService.class);
    private final AudienceAnalysisBatchInputRestorer restorer = mock(AudienceAnalysisBatchInputRestorer.class);
    private final AiAnalysisJsonParser parser = mock(AiAnalysisJsonParser.class);
    private final AiAnalysisCompletionService completions = mock(AiAnalysisCompletionService.class);

    @Test
    void 경쟁_작업자의_배치_조회_DB_오류는_정상_부모를_FAILED로_바꾸지_않는다() {
        var coordinator = coordinator();
        when(batchService.process(eq(10L), eq(Duration.ofSeconds(90)), any()))
                .thenThrow(new DataAccessResourceFailureException("temporary database failure"));

        var result = coordinator.resume(1L, 10L, false, () -> true);

        assertThat(result.status()).isEqualTo(AiAnalysisStatus.PROCESSING);
        verify(analyses, never()).markFailed(anyLong(), any());
    }

    @Test
    void 최종_그룹_저장_DB_오류는_완료된_배치를_보존하고_부모를_PROCESSING으로_남긴다() {
        var coordinator = coordinator();
        AudienceAnalysisBatch input = new AudienceAnalysisBatch(0,
                List.of(new AudienceAnalysisInputMessage(11L, "질문", 1)), Map.of(11L, List.of(11L)));
        AiAnalysisResult result = new AiAnalysisResult(List.of(
                new AiTopicResult("주제", "요약", TopicCategory.QUESTION, List.of(11L))));
        var stored = new AiAnalysisBatchRepository.Batch(101L, 10L, 0, AiAnalysisBatchStatus.COMPLETED,
                AudienceAnalysisBatchManifest.from(input), 1, 2, result, "fake-model", null, null,
                null, null, null, null);
        when(batchService.process(eq(10L), eq(Duration.ofSeconds(90)), any()))
                .thenReturn(new AudienceAnalysisBatchService.RunResult(0,
                        AudienceAnalysisBatchService.BatchOutcome.ALL_BATCHES_COMPLETED));
        when(analyses.findById(10L)).thenReturn(Optional.of(analysis()));
        when(batches.findByAnalysis(10L)).thenReturn(List.of(stored));
        when(batches.findResultJson(101L)).thenReturn("{\"topics\":[]}");
        when(restorer.restore(analysis(), stored)).thenReturn(input);
        when(parser.parse("{\"topics\":[]}")).thenReturn(result);
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("temporary database failure")).doNothing()
                .when(completions).completeWithTopics(1L, 10L, result, "fake-model");

        var resumed = coordinator.resume(1L, 10L, false, () -> true);

        assertThat(resumed.status()).isEqualTo(AiAnalysisStatus.PROCESSING);
        verify(analyses, never()).markFailed(anyLong(), any());
        verify(completions).completeWithTopics(1L, 10L, result, "fake-model");

        var retried = coordinator.resume(1L, 10L, false, () -> true);

        assertThat(retried.status()).isEqualTo(AiAnalysisStatus.COMPLETED);
        verify(analyses, never()).markFailed(anyLong(), any());
    }

    private AudienceAnalysisBatchCoordinator coordinator() {
        when(analyses.findById(10L)).thenReturn(Optional.of(analysis()));
        return new AudienceAnalysisBatchCoordinator(analyses, batches, splitter, batchService, restorer, parser,
                completions, new GeminiAudienceAnalysisProperties("", "fake", "", null, null,
                100, 32_000, 2_048, 128_000), new AiAnalysisSchedulerProperties(3));
    }

    private AiAnalysisRepository.Analysis analysis() {
        return new AiAnalysisRepository.Analysis(10L, 1L, java.time.LocalDateTime.of(2026, 1, 1, 10, 0),
                java.time.LocalDateTime.of(2026, 1, 1, 10, 0, 30), AiAnalysisStatus.PROCESSING);
    }
}
