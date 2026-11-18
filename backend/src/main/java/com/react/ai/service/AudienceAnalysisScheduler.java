package com.react.ai.service;

import com.react.ai.repository.AiAnalysisRepository;
import com.react.ai.repository.AiAnalysisTargetRoomRepository;
import com.react.ai.config.AiAnalysisSchedulerProperties;
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

    public AudienceAnalysisScheduler(AiAnalysisTargetRoomRepository rooms, AiAnalysisRepository analyses,
                                     AudienceAnalysisService service, AiAnalysisWindowCalculator calculator,
                                     Clock clock, AiAnalysisSchedulerProperties properties) {
        this.rooms = rooms;
        this.analyses = analyses;
        this.service = service;
        this.calculator = calculator;
        this.clock = clock;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.ai.audience.scheduler.fixed-delay:5000}")
    public void processCompletedRegularWindows() {
        LocalDateTime now = LocalDateTime.now(clock);
        for (AiAnalysisTargetRoomRepository.LiveRoom room : rooms.findLiveRoomsWithStartedAt()) {
            processRoom(room, now);
        }
    }

    private void processRoom(AiAnalysisTargetRoomRepository.LiveRoom room, LocalDateTime now) {
        try {
            var windows = calculator.completedWindows(room.startedAt(), now);
            if (windows.isEmpty()) {
                return;
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
                processWindow(room.roomId(), window);
                processedCount++;
            }
        } catch (RuntimeException e) {
            log.warn("AI 정규 분석 강연방 처리에 실패했습니다. roomId={}", room.roomId(), e);
        }
    }

    private void processWindow(long roomId, AiAnalysisWindowCalculator.AnalysisWindow window) {
        try {
            service.process(roomId, window.startAt(), window.endAt());
        } catch (RuntimeException e) {
            log.warn("AI 정규 분석 구간 처리에 실패했습니다. roomId={}, windowStartAt={}", roomId, window.startAt(), e);
        }
    }
}
