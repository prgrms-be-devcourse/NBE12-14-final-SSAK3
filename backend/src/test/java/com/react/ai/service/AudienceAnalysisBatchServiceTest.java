package com.react.ai.service;

import com.react.ai.config.AudienceAnalysisCostProperties;
import com.react.ai.config.AudienceAnalysisRetryProperties;
import com.react.ai.domain.AiAnalysisBatchStatus;
import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.repository.AiAnalysisBatchAttemptRepository;
import com.react.ai.repository.AiAnalysisBatchRepository;
import com.react.ai.repository.AiAnalysisRepository;
import com.react.ai.repository.AiLlmCallUsageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AudienceAnalysisBatchServiceTest {
    @Test
    void claim_후_입력_조회_DB_오류는_배치를_FAILED로_종결하지_않는다() {
        AiAnalysisRepository analyses = mock(AiAnalysisRepository.class);
        AiAnalysisBatchRepository batches = mock(AiAnalysisBatchRepository.class);
        AudienceAnalysisBatchInputRestorer restorer = mock(AudienceAnalysisBatchInputRestorer.class);
        AudienceAnalysisBatchService service = new AudienceAnalysisBatchService(analyses, batches,
                mock(AiAnalysisBatchAttemptRepository.class), restorer, mock(AudienceAnalysisLlmClient.class),
                mock(AiAnalysisJsonParser.class), mock(AiLlmCallUsageRepository.class),
                new AudienceAnalysisRetryProperties(1, Duration.ZERO, Duration.ofSeconds(5)),
                new AudienceAnalysisCostProperties(null, null));
        var analysis = new AiAnalysisRepository.Analysis(10L, 1L,
                LocalDateTime.of(2026, 1, 1, 10, 0), LocalDateTime.of(2026, 1, 1, 10, 0, 30),
                AiAnalysisStatus.PROCESSING);
        var pending = new AiAnalysisBatchRepository.Batch(20L, 10L, 0, AiAnalysisBatchStatus.PENDING,
                null, 1, 1, null, null, null, null, null, null, null, null);
        when(analyses.findById(10L)).thenReturn(Optional.of(analysis));
        when(batches.findByAnalysis(10L)).thenReturn(List.of(pending));
        when(batches.claim(anyLong(), any(), any())).thenReturn(true);
        when(restorer.restore(analysis, pending))
                .thenThrow(new DataAccessResourceFailureException("temporary database failure"));

        var result = service.process(10L, Duration.ofSeconds(90));

        assertThat(result.outcome()).isEqualTo(AudienceAnalysisBatchService.BatchOutcome.IN_PROGRESS);
        verify(batches, never()).markFailed(anyLong(), any(), any());
    }
}
