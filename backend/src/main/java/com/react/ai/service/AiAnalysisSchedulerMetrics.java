package com.react.ai.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** AI 스케줄러의 실행 단위 관측값을 기록한다. 계측 실패는 분석 작업에 영향을 주지 않는다. */
@Component
@RequiredArgsConstructor
public class AiAnalysisSchedulerMetrics {
    private static final Logger log = LoggerFactory.getLogger(AiAnalysisSchedulerMetrics.class);

    private final MeterRegistry registry;

    public void recordLiveRun(LiveRun run) {
        safely("live", () -> {
            increment("react.ai.scheduler.live.executions");
            record("react.ai.scheduler.live.target.rooms", run.targetRooms());
            record("react.ai.scheduler.live.processed.windows", run.processedWindows());
            record("react.ai.scheduler.live.completed.windows", run.completedWindows());
            record("react.ai.scheduler.live.failed.windows", run.failedWindows());
            record("react.ai.scheduler.live.empty.windows", run.emptyWindows());
            if (run.remainingWindows() != null) {
                record("react.ai.scheduler.live.remaining.windows", run.remainingWindows());
            }
            incrementBy("react.ai.scheduler.live.errors", run.errors());
            recordTime("react.ai.scheduler.live.duration", run.duration());
        });
    }

    public void recordFinalizationRun(FinalizationRun run) {
        safely("finalization", () -> {
            increment("react.ai.scheduler.finalization.executions");
            record("react.ai.scheduler.finalization.target.rooms", run.targetRooms());
            record("react.ai.scheduler.finalization.completed.rooms", run.completedRooms());
            record("react.ai.scheduler.finalization.failed.rooms", run.failedRooms());
            record("react.ai.scheduler.finalization.deferred.rooms", run.deferredRooms());
            record("react.ai.scheduler.finalization.failed.analysis.windows", run.failedAnalysisWindows());
            incrementBy("react.ai.scheduler.finalization.errors", run.errors());
            recordTime("react.ai.scheduler.finalization.duration", run.duration());
        });
    }

    public void recordStaleRecoveryRun(int recoveredAnalyses, Duration duration) {
        safely("stale-recovery", () -> {
            increment("react.ai.scheduler.stale-recovery.executions");
            record("react.ai.scheduler.stale-recovery.recovered.analyses", recoveredAnalyses);
            recordTime("react.ai.scheduler.stale-recovery.duration", duration);
        });
    }

    public void recordStaleRecoveryFailure(Duration duration) {
        safely("stale-recovery", () -> {
            increment("react.ai.scheduler.stale-recovery.executions");
            increment("react.ai.scheduler.stale-recovery.errors");
            recordTime("react.ai.scheduler.stale-recovery.duration", duration);
        });
    }

    private void increment(String name) {
        Counter.builder(name).register(registry).increment();
    }

    private void incrementBy(String name, int amount) {
        if (amount > 0) {
            Counter.builder(name).register(registry).increment(amount);
        }
    }

    private void record(String name, int amount) {
        DistributionSummary.builder(name).register(registry).record(amount);
    }

    private void recordTime(String name, Duration duration) {
        Timer.builder(name).register(registry).record(duration);
    }

    private void safely(String scheduler, Runnable metricRecording) {
        try {
            metricRecording.run();
        } catch (RuntimeException e) {
            log.debug("AI 스케줄러 메트릭 기록에 실패했습니다. scheduler={}", scheduler, e);
        }
    }

    public record LiveRun(int targetRooms, int processedWindows, int completedWindows, int failedWindows,
                          int emptyWindows, Integer remainingWindows, int errors, Duration duration) {
    }

    public record FinalizationRun(int targetRooms, int completedRooms, int failedRooms, int deferredRooms,
                                  int failedAnalysisWindows, int errors, Duration duration) {
    }
}
