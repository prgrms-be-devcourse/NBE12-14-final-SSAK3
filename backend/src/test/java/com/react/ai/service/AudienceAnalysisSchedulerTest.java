package com.react.ai.service;

import com.react.ai.repository.AiAnalysisRepository;
import com.react.ai.repository.AiAnalysisTargetRoomRepository;
import com.react.ai.config.AiAnalysisSchedulerProperties;
import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.dto.AiAnalysisProcessResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AudienceAnalysisSchedulerTest {
    private final AiAnalysisTargetRoomRepository rooms = mock(AiAnalysisTargetRoomRepository.class);
    private final AiAnalysisRepository analyses = mock(AiAnalysisRepository.class);
    private final AudienceAnalysisService service = mock(AudienceAnalysisService.class);
    private final AiAnalysisWindowCalculator calculator = new AiAnalysisWindowCalculator();
    private final LocalDateTime startedAt = LocalDateTime.of(2026, 11, 18, 10, 0);
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 18, 10, 2, 5);
    private SimpleMeterRegistry meterRegistry;
    private AudienceAnalysisScheduler scheduler;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(now.atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"));
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new AudienceAnalysisScheduler(rooms, analyses, service, calculator, clock, properties(3),
                new AiAnalysisSchedulerMetrics(meterRegistry));
    }

    @Test
    void 재시작_후_기록되지_않은_완료_구간을_시간순으로_처리한다() {
        when(rooms.findLiveRoomsWithStartedAt()).thenReturn(List.of(room(10L, startedAt)));
        when(analyses.findRecordedWindowStarts(10L, startedAt, now)).thenReturn(Set.of(startedAt));

        scheduler.processCompletedRegularWindows();

        InOrder order = inOrder(service);
        order.verify(service).process(10L, startedAt.plusSeconds(30), startedAt.plusSeconds(60));
        order.verify(service).process(10L, startedAt.plusSeconds(60), startedAt.plusSeconds(90));
        order.verify(service).process(10L, startedAt.plusSeconds(90), startedAt.plusSeconds(120));
        verifyNoMoreInteractions(service);
    }

    @Test
    void 정규_구간_처리는_stale_복구_SQL을_실행하지_않는다() {
        when(rooms.findLiveRoomsWithStartedAt()).thenReturn(List.of());

        scheduler.processCompletedRegularWindows();

        verify(analyses, never()).abortStaleInFlight(any());
    }

    @Test
    void 이미_기록된_구간은_서비스와_LLM을_다시_호출하지_않는다() {
        when(rooms.findLiveRoomsWithStartedAt()).thenReturn(List.of(room(10L, startedAt)));
        when(analyses.findRecordedWindowStarts(10L, startedAt, now)).thenReturn(Set.of(
                startedAt, startedAt.plusSeconds(30), startedAt.plusSeconds(60), startedAt.plusSeconds(90)));

        scheduler.processCompletedRegularWindows();

        verifyNoInteractions(service);
    }

    @Test
    void 한_강연방_구간_오류가_다른_강연방_처리를_막지_않는다() {
        LocalDateTime oneWindowNow = startedAt.plusSeconds(30);
        Clock clock = Clock.fixed(oneWindowNow.atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"));
        scheduler = new AudienceAnalysisScheduler(rooms, analyses, service, calculator, clock, properties(3),
                new AiAnalysisSchedulerMetrics(meterRegistry));
        when(rooms.findLiveRoomsWithStartedAt()).thenReturn(List.of(room(10L, startedAt), room(20L, startedAt)));
        when(analyses.findRecordedWindowStarts(anyLong(), eq(startedAt), eq(oneWindowNow))).thenReturn(Set.of());
        doThrow(new IllegalStateException("failure")).when(service).process(10L, startedAt, oneWindowNow);

        scheduler.processCompletedRegularWindows();

        verify(service).process(10L, startedAt, oneWindowNow);
        verify(service).process(20L, startedAt, oneWindowNow);
    }

    @Test
    void 한_구간_오류가_같은_강연방의_다음_구간을_막지_않는다() {
        when(rooms.findLiveRoomsWithStartedAt()).thenReturn(List.of(room(10L, startedAt)));
        when(analyses.findRecordedWindowStarts(10L, startedAt, now)).thenReturn(Set.of());
        doThrow(new IllegalStateException("failure")).when(service)
                .process(10L, startedAt, startedAt.plusSeconds(30));

        scheduler.processCompletedRegularWindows();

        verify(service).process(10L, startedAt, startedAt.plusSeconds(30));
        verify(service).process(10L, startedAt.plusSeconds(30), startedAt.plusSeconds(60));
        verify(service).process(10L, startedAt.plusSeconds(60), startedAt.plusSeconds(90));
    }

    @Test
    void 강연방별_최대_구간_수만_처리하고_다음_실행에서_남은_구간을_이어서_처리한다() {
        when(rooms.findLiveRoomsWithStartedAt()).thenReturn(List.of(room(10L, startedAt)));
        doReturn(Set.of())
                .doReturn(Set.of(startedAt, startedAt.plusSeconds(30), startedAt.plusSeconds(60)))
                .doReturn(Set.of(startedAt, startedAt.plusSeconds(30), startedAt.plusSeconds(60)))
                .doReturn(Set.of(startedAt, startedAt.plusSeconds(30), startedAt.plusSeconds(60),
                        startedAt.plusSeconds(90)))
                .when(analyses).findRecordedWindowStarts(10L, startedAt, now);

        scheduler.processCompletedRegularWindows();
        scheduler.processCompletedRegularWindows();

        InOrder order = inOrder(service);
        order.verify(service).process(10L, startedAt, startedAt.plusSeconds(30));
        order.verify(service).process(10L, startedAt.plusSeconds(30), startedAt.plusSeconds(60));
        order.verify(service).process(10L, startedAt.plusSeconds(60), startedAt.plusSeconds(90));
        order.verify(service).process(10L, startedAt.plusSeconds(90), startedAt.plusSeconds(120));
        verifyNoMoreInteractions(service);
    }

    @Test
    void 최대_처리_구간_수는_각_강연방에_독립적으로_적용된다() {
        when(rooms.findLiveRoomsWithStartedAt()).thenReturn(List.of(room(10L, startedAt), room(20L, startedAt)));
        when(analyses.findRecordedWindowStarts(anyLong(), eq(startedAt), eq(now))).thenReturn(Set.of());

        scheduler.processCompletedRegularWindows();

        verify(service, times(3)).process(eq(10L), any(), any());
        verify(service, times(3)).process(eq(20L), any(), any());
    }

    @Test
    void LIVE_실행의_완료_실패_빈_구간과_남은_구간을_실제_처리_결과로_기록한다() {
        LocalDateTime oneWindowNow = startedAt.plusSeconds(30);
        Clock clock = Clock.fixed(oneWindowNow.atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"));
        scheduler = new AudienceAnalysisScheduler(rooms, analyses, service, calculator, clock, properties(3),
                new AiAnalysisSchedulerMetrics(meterRegistry));
        when(rooms.findLiveRoomsWithStartedAt()).thenReturn(List.of(room(10L, startedAt), room(20L, startedAt)));
        when(analyses.findRecordedWindowStarts(10L, startedAt, oneWindowNow))
                .thenReturn(Set.of(), Set.of(startedAt));
        when(analyses.findRecordedWindowStarts(20L, startedAt, oneWindowNow))
                .thenReturn(Set.of(), Set.of(startedAt));
        when(service.process(10L, startedAt, oneWindowNow))
                .thenReturn(new AiAnalysisProcessResult(101L, AiAnalysisStatus.COMPLETED, true, 0));
        when(service.process(20L, startedAt, oneWindowNow))
                .thenReturn(new AiAnalysisProcessResult(201L, AiAnalysisStatus.FAILED, true, 0));
        when(analyses.isCompletedEmpty(101L)).thenReturn(true);

        scheduler.processCompletedRegularWindows();

        assertThat(meterRegistry.get("react.ai.scheduler.live.executions").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("react.ai.scheduler.live.target.rooms").summary().totalAmount()).isEqualTo(2);
        assertThat(meterRegistry.get("react.ai.scheduler.live.processed.windows").summary().totalAmount()).isEqualTo(2);
        assertThat(meterRegistry.get("react.ai.scheduler.live.completed.windows").summary().totalAmount()).isEqualTo(1);
        assertThat(meterRegistry.get("react.ai.scheduler.live.failed.windows").summary().totalAmount()).isEqualTo(1);
        assertThat(meterRegistry.get("react.ai.scheduler.live.empty.windows").summary().totalAmount()).isEqualTo(1);
        assertThat(meterRegistry.get("react.ai.scheduler.live.remaining.windows").summary().totalAmount()).isZero();
        assertThat(meterRegistry.get("react.ai.scheduler.live.duration").timer().count()).isEqualTo(1);
    }

    private AiAnalysisTargetRoomRepository.LiveRoom room(long roomId, LocalDateTime roomStartedAt) {
        return new AiAnalysisTargetRoomRepository.LiveRoom(roomId, roomStartedAt);
    }

    private AiAnalysisSchedulerProperties properties(int maxWindowsPerRun) {
        return new AiAnalysisSchedulerProperties(maxWindowsPerRun);
    }
}
