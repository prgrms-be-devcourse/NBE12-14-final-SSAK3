package com.react.ai.service;

import com.react.ai.repository.AiAnalysisRepository;
import com.react.ai.repository.AiAnalysisTargetRoomRepository;
import com.react.ai.config.AiAnalysisSchedulerProperties;
import com.react.ai.dto.AiAnalysisProcessResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;

@Component
@Profile("!test")
public class AudienceAnalysisScheduler {
    private static final Logger log = LoggerFactory.getLogger(AudienceAnalysisScheduler.class);

    private final AiAnalysisTargetRoomRepository rooms;
    private final AiAnalysisRepository analyses;
    private final AudienceAnalysisService service;
    private final AiAnalysisWindowCalculator calculator;
    private final Clock clock;
    private final AiAnalysisSchedulerProperties properties;
    private final AiAnalysisSchedulerMetrics metrics;
    public AudienceAnalysisScheduler(AiAnalysisTargetRoomRepository rooms, AiAnalysisRepository analyses,
                                     AudienceAnalysisService service, AiAnalysisWindowCalculator calculator,
                                     Clock clock, AiAnalysisSchedulerProperties properties,
                                     AiAnalysisSchedulerMetrics metrics) {
        this.rooms = rooms;
        this.analyses = analyses;
        this.service = service;
        this.calculator = calculator;
        this.clock = clock;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${app.ai.audience.scheduler.fixed-delay:5000}",
            scheduler = "aiLiveAnalysisTaskScheduler")
    public void processCompletedRegularWindows() {
        long startedAtNanos = System.nanoTime();
        LiveRunAccumulator run = new LiveRunAccumulator();
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            var liveRooms = rooms.findLiveRoomsWithStartedAt();
            run.targetRooms = liveRooms.size();
            for (AiAnalysisTargetRoomRepository.LiveRoom room : liveRooms) {
                run.add(processRoom(room, now));
            }
        } catch (RuntimeException e) {
            run.errors++;
            run.remainingKnown = false;
            log.warn("AI 정규 분석 스케줄러 실행에 실패했습니다.", e);
        } finally {
            metrics.recordLiveRun(run.toMetric(java.time.Duration.ofNanos(System.nanoTime() - startedAtNanos)));
        }
    }

    private RoomRun processRoom(AiAnalysisTargetRoomRepository.LiveRoom room, LocalDateTime now) {
        RoomRun run = new RoomRun();
        try {
            var windows = calculator.completedWindows(room.startedAt(), now);
            if (windows.isEmpty()) {
                run.remainingWindows = 0;
                return run;
            }
            Set<LocalDateTime> recorded = analyses.findRecordedWindowStarts(room.roomId(), room.startedAt(), now);
            int processedCount = 0;
            for (AiAnalysisWindowCalculator.AnalysisWindow window : windows) {
                if (recorded.contains(window.startAt())) {
                    continue;
                }
                if (processedCount >= properties.maxWindowsPerRun()) {
                    break;
                }
                AiAnalysisProcessResult result = processWindow(room.roomId(), window);
                processedCount++;
                if (result == null || !result.created()) {
                    if (result == null) {
                        run.errors++;
                    }
                    continue;
                }
                run.processedWindows++;
                if (result.status() == com.react.ai.domain.AiAnalysisStatus.COMPLETED) {
                    run.completedWindows++;
                    if (isCompletedEmpty(result.analysisId())) {
                        run.emptyWindows++;
                    }
                } else if (result.status() == com.react.ai.domain.AiAnalysisStatus.FAILED) {
                    run.failedWindows++;
                }
            }
            run.remainingWindows = remainingWindowCount(room.roomId(), room.startedAt(), now, windows);
        } catch (RuntimeException e) {
            run.errors++;
            log.warn("AI 정규 분석 강연방 처리에 실패했습니다. roomId={}", room.roomId(), e);
        }
        return run;
    }

    private AiAnalysisProcessResult processWindow(long roomId, AiAnalysisWindowCalculator.AnalysisWindow window) {
        try {
            return service.process(roomId, window.startAt(), window.endAt());
        } catch (RuntimeException e) {
            log.warn("AI 정규 분석 구간 처리에 실패했습니다. roomId={}, windowStartAt={}", roomId, window.startAt(), e);
            return null;
        }
    }

    private boolean isCompletedEmpty(long analysisId) {
        try {
            return analyses.isCompletedEmpty(analysisId);
        } catch (RuntimeException e) {
            log.debug("AI 빈 분석 구간 메트릭 확인에 실패했습니다. analysisId={}", analysisId, e);
            return false;
        }
    }

    private Integer remainingWindowCount(long roomId, LocalDateTime startedAt, LocalDateTime now,
                                         java.util.List<AiAnalysisWindowCalculator.AnalysisWindow> windows) {
        try {
            Set<LocalDateTime> recorded = analyses.findRecordedWindowStarts(roomId, startedAt, now);
            return (int) windows.stream().filter(window -> !recorded.contains(window.startAt())).count();
        } catch (RuntimeException e) {
            log.debug("AI 미처리 분석 구간 메트릭 확인에 실패했습니다. roomId={}", roomId, e);
            return null;
        }
    }

    private static final class RoomRun {
        private int processedWindows;
        private int completedWindows;
        private int failedWindows;
        private int emptyWindows;
        private Integer remainingWindows;
        private int errors;
    }

    private static final class LiveRunAccumulator {
        private int targetRooms;
        private int processedWindows;
        private int completedWindows;
        private int failedWindows;
        private int emptyWindows;
        private int remainingWindows;
        private boolean remainingKnown = true;
        private int errors;

        private void add(RoomRun room) {
            processedWindows += room.processedWindows;
            completedWindows += room.completedWindows;
            failedWindows += room.failedWindows;
            emptyWindows += room.emptyWindows;
            errors += room.errors;
            if (room.remainingWindows == null) {
                remainingKnown = false;
            } else {
                remainingWindows += room.remainingWindows;
            }
        }

        private AiAnalysisSchedulerMetrics.LiveRun toMetric(java.time.Duration duration) {
            return new AiAnalysisSchedulerMetrics.LiveRun(targetRooms, processedWindows, completedWindows,
                    failedWindows, emptyWindows, remainingKnown ? remainingWindows : null, errors, duration);
        }
    }
}
