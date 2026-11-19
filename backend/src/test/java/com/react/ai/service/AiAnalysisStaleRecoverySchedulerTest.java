package com.react.ai.service;

import com.react.ai.config.AiAnalysisSchedulerProperties;
import com.react.ai.repository.AiAnalysisRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.mockito.Mockito.*;

class AiAnalysisStaleRecoverySchedulerTest {
    private final AiAnalysisRepository analyses = mock(AiAnalysisRepository.class);
    private final AiAnalysisSchedulerProperties properties =
            new AiAnalysisSchedulerProperties(3, 5, Duration.ofSeconds(90), Duration.ofMinutes(2));
    private final AiAnalysisStaleRecoveryScheduler scheduler =
            new AiAnalysisStaleRecoveryScheduler(analyses, properties);

    @Test
    void stale_분석_복구에_기존_기준_시간을_사용한다() {
        when(analyses.abortStaleInFlight(Duration.ofMinutes(2))).thenReturn(2);

        scheduler.recoverStaleAnalyses();

        verify(analyses).abortStaleInFlight(Duration.ofMinutes(2));
    }

    @Test
    void 복구_대상이_없어도_정상_종료한다() {
        when(analyses.abortStaleInFlight(Duration.ofMinutes(2))).thenReturn(0);

        scheduler.recoverStaleAnalyses();

        verify(analyses).abortStaleInFlight(Duration.ofMinutes(2));
    }

    @Test
    void 복구_SQL_오류_후에도_다음_실행을_계속한다() {
        when(analyses.abortStaleInFlight(Duration.ofMinutes(2)))
                .thenThrow(new IllegalStateException("database error"))
                .thenReturn(1);

        scheduler.recoverStaleAnalyses();
        scheduler.recoverStaleAnalyses();

        verify(analyses, times(2)).abortStaleInFlight(Duration.ofMinutes(2));
    }
}
