package com.react.ai.service;

import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.repository.AiAnalysisFinalizationRepository;
import com.react.ai.repository.AiAnalysisRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Profile("!test")
@RequiredArgsConstructor
public class AiAnalysisFinalizationService {
    private static final Logger log = LoggerFactory.getLogger(AiAnalysisFinalizationService.class);

    private final AiAnalysisFinalizationRepository finalizations;
    private final AiAnalysisRepository analyses;
    private final AudienceAnalysisService audienceAnalysisService;
    private final AiAnalysisWindowCalculator calculator;

    public void processPendingRooms(int maxRooms, int maxWindowsPerRoom, Duration leaseDuration) {
        for (AiAnalysisFinalizationRepository.EndedRoom room : finalizations.findCandidates(maxRooms)) {
            try {
                processRoom(room, maxWindowsPerRoom, leaseDuration);
            } catch (RuntimeException e) {
                log.warn("AI 종료 finalization 처리에 실패했습니다. roomId={}", room.roomId(), e);
            }
        }
    }

    void processRoom(AiAnalysisFinalizationRepository.EndedRoom room, int maxWindowsPerRoom,
                     Duration leaseDuration) {
        finalizations.ensurePending(room);
        UUID claimToken = UUID.randomUUID();
        if (!finalizations.claim(room, claimToken, leaseDuration)) {
            return;
        }

        try {
            List<AiAnalysisWindowCalculator.AnalysisWindow> expected =
                    calculator.finalizationWindows(room.startedAt(), room.endedAt());
            Map<WindowKey, AiAnalysisStatus> statuses = statuses(room, expected);
            int processed = 0;
            for (AiAnalysisWindowCalculator.AnalysisWindow window : expected) {
                if (statuses.containsKey(WindowKey.of(window))) {
                    continue;
                }
                if (processed >= maxWindowsPerRoom) {
                    break;
                }
                if (!finalizations.renewLease(room.roomId(), claimToken, leaseDuration)) {
                    return;
                }
                audienceAnalysisService.process(room.roomId(), window.startAt(), window.endAt());
                processed++;
                if (!finalizations.renewLease(room.roomId(), claimToken, leaseDuration)) {
                    return;
                }
            }

            Map<WindowKey, AiAnalysisStatus> latest = statuses(room, expected);
            if (allTerminal(expected, latest)) {
                finalizations.complete(room.roomId(), claimToken, failedCount(expected, latest));
            } else {
                finalizations.release(room.roomId(), claimToken);
            }
        } catch (IllegalArgumentException e) {
            finalizations.markFailed(room.roomId(), claimToken, "INVALID_LECTURE_TIME");
        } catch (RuntimeException e) {
            finalizations.release(room.roomId(), claimToken);
            throw e;
        }
    }

    private Map<WindowKey, AiAnalysisStatus> statuses(AiAnalysisFinalizationRepository.EndedRoom room,
                                                       List<AiAnalysisWindowCalculator.AnalysisWindow> expected) {
        if (expected.isEmpty()) {
            return Map.of();
        }
        Map<WindowKey, AiAnalysisStatus> statuses = new HashMap<>();
        for (AiAnalysisRepository.Analysis analysis : analyses.findWithin(
                room.roomId(), room.startedAt(), room.endedAt())) {
            statuses.put(new WindowKey(analysis.windowStartAt(), analysis.windowEndAt()), analysis.status());
        }
        return statuses;
    }

    private boolean allTerminal(List<AiAnalysisWindowCalculator.AnalysisWindow> expected,
                                Map<WindowKey, AiAnalysisStatus> statuses) {
        return expected.stream().allMatch(window -> isTerminal(statuses.get(WindowKey.of(window))));
    }

    private int failedCount(List<AiAnalysisWindowCalculator.AnalysisWindow> expected,
                            Map<WindowKey, AiAnalysisStatus> statuses) {
        return (int) expected.stream()
                .filter(window -> statuses.get(WindowKey.of(window)) == AiAnalysisStatus.FAILED)
                .count();
    }

    private boolean isTerminal(AiAnalysisStatus status) {
        return status == AiAnalysisStatus.COMPLETED || status == AiAnalysisStatus.FAILED;
    }

    private record WindowKey(java.time.LocalDateTime startAt, java.time.LocalDateTime endAt) {
        static WindowKey of(AiAnalysisWindowCalculator.AnalysisWindow window) {
            return new WindowKey(window.startAt(), window.endAt());
        }
    }
}
