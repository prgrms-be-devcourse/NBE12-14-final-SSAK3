package com.react.ai.service;

import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.repository.AiAnalysisFinalizationRepository;
import com.react.ai.repository.AiAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiAnalysisFinalizationServiceTest {
    private final AiAnalysisFinalizationRepository finalizations = mock(AiAnalysisFinalizationRepository.class);
    private final AiAnalysisRepository analyses = mock(AiAnalysisRepository.class);
    private final AudienceAnalysisService audience = mock(AudienceAnalysisService.class);
    private final AiAnalysisWindowCalculator calculator = new AiAnalysisWindowCalculator();
    private final LocalDateTime startedAt = LocalDateTime.of(2026, 11, 18, 10, 0);
    private final LocalDateTime endedAt = startedAt.plusSeconds(75);
    private final AiAnalysisFinalizationRepository.EndedRoom room =
            new AiAnalysisFinalizationRepository.EndedRoom(10L, startedAt, endedAt);
    private AiAnalysisFinalizationService service;

    @BeforeEach
    void setUp() {
        service = new AiAnalysisFinalizationService(finalizations, analyses, audience, calculator);
        when(finalizations.claim(eq(room), any(), eq(Duration.ofSeconds(90)))).thenReturn(true);
        when(finalizations.renewLease(eq(10L), any(), eq(Duration.ofSeconds(90)))).thenReturn(true);
    }

    @Test
    void 누락된_정규_구간과_잔여_구간을_오래된_순서로_최대_세개_처리한다() {
        when(analyses.findWithin(10L, startedAt, endedAt))
                .thenReturn(List.of())
                .thenReturn(List.of(
                        analysis(startedAt, startedAt.plusSeconds(30), AiAnalysisStatus.COMPLETED),
                        analysis(startedAt.plusSeconds(30), startedAt.plusSeconds(60), AiAnalysisStatus.COMPLETED),
                        analysis(startedAt.plusSeconds(60), endedAt, AiAnalysisStatus.COMPLETED)
                ));

        service.processRoom(room, 3, Duration.ofSeconds(90));

        var order = inOrder(audience);
        order.verify(audience).process(10L, startedAt, startedAt.plusSeconds(30));
        order.verify(audience).process(10L, startedAt.plusSeconds(30), startedAt.plusSeconds(60));
        order.verify(audience).process(10L, startedAt.plusSeconds(60), endedAt);
        verify(finalizations).complete(eq(10L), any(), eq(0));
    }

    @Test
    void PENDING이나_PROCESSING_분석이_있으면_finalization을_완료하지_않는다() {
        when(analyses.findWithin(10L, startedAt, endedAt)).thenReturn(List.of(
                analysis(startedAt, startedAt.plusSeconds(30), AiAnalysisStatus.COMPLETED),
                analysis(startedAt.plusSeconds(30), startedAt.plusSeconds(60), AiAnalysisStatus.PENDING),
                analysis(startedAt.plusSeconds(60), endedAt, AiAnalysisStatus.PROCESSING)
        ));

        service.processRoom(room, 3, Duration.ofSeconds(90));

        verify(finalizations).release(eq(10L), any());
        verify(finalizations, never()).complete(anyLong(), any(), anyInt());
        verifyNoInteractions(audience);
    }

    @Test
    void FAILED_분석은_종결로_보고_실패_개수를_기록한다() {
        when(analyses.findWithin(10L, startedAt, endedAt)).thenReturn(List.of(
                analysis(startedAt, startedAt.plusSeconds(30), AiAnalysisStatus.COMPLETED),
                analysis(startedAt.plusSeconds(30), startedAt.plusSeconds(60), AiAnalysisStatus.FAILED),
                analysis(startedAt.plusSeconds(60), endedAt, AiAnalysisStatus.COMPLETED)
        ));

        service.processRoom(room, 3, Duration.ofSeconds(90));

        verify(finalizations).complete(eq(10L), any(), eq(1));
        verifyNoInteractions(audience);
    }

    @Test
    void lease를_갱신하지_못하면_더_이상_Gemini_호출을_시작하지_않는다() {
        when(analyses.findWithin(10L, startedAt, endedAt)).thenReturn(List.of());
        when(finalizations.renewLease(eq(10L), any(), eq(Duration.ofSeconds(90)))).thenReturn(false);

        service.processRoom(room, 3, Duration.ofSeconds(90));

        verifyNoInteractions(audience);
        verify(finalizations, never()).complete(anyLong(), any(), anyInt());
    }

    @Test
    void Gemini_호출_중_lease가_만료되면_finalization을_완료하지_않는다() {
        when(analyses.findWithin(10L, startedAt, endedAt)).thenReturn(List.of());
        when(finalizations.renewLease(eq(10L), any(), eq(Duration.ofSeconds(90))))
                .thenReturn(true, false);

        service.processRoom(room, 3, Duration.ofSeconds(90));

        verify(audience).process(10L, startedAt, startedAt.plusSeconds(30));
        verify(finalizations, never()).complete(anyLong(), any(), anyInt());
        verify(finalizations, never()).release(anyLong(), any());
    }

    @Test
    void 시작과_종료_시각이_같은_빈_강연은_분석을_호출하지_않고_정상_완료한다() {
        AiAnalysisFinalizationRepository.EndedRoom emptyRoom =
                new AiAnalysisFinalizationRepository.EndedRoom(10L, startedAt, startedAt);
        when(finalizations.claim(eq(emptyRoom), any(), eq(Duration.ofSeconds(90)))).thenReturn(true);

        service.processRoom(emptyRoom, 3, Duration.ofSeconds(90));

        verifyNoInteractions(audience);
        verify(finalizations).complete(eq(10L), any(), eq(0));
        verify(finalizations, never()).release(anyLong(), any());
    }

    private AiAnalysisRepository.Analysis analysis(LocalDateTime start, LocalDateTime end, AiAnalysisStatus status) {
        return new AiAnalysisRepository.Analysis(1L, 10L, start, end, status);
    }
}
