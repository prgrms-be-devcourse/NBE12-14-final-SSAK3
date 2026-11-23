package com.react.ai.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class AiAnalysisWindowCalculator {
    public static final Duration WINDOW_SIZE = Duration.ofSeconds(30);

    public List<AnalysisWindow> completedWindows(LocalDateTime startedAt, LocalDateTime now) {
        if (startedAt == null || now == null || now.isBefore(startedAt.plus(WINDOW_SIZE))) {
            return List.of();
        }

        List<AnalysisWindow> windows = new ArrayList<>();
        LocalDateTime windowStartAt = startedAt;
        while (!windowStartAt.plus(WINDOW_SIZE).isAfter(now)) {
            LocalDateTime windowEndAt = windowStartAt.plus(WINDOW_SIZE);
            windows.add(new AnalysisWindow(windowStartAt, windowEndAt));
            windowStartAt = windowEndAt;
        }
        return List.copyOf(windows);
    }

    /** 강연 종료 시각까지의 정규 구간과, 필요할 때만 마지막 잔여 구간을 계산한다. */
    public List<AnalysisWindow> finalizationWindows(LocalDateTime startedAt, LocalDateTime endedAt) {
        if (startedAt == null || endedAt == null || endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("강연 종료 시각이 올바르지 않습니다.");
        }

        List<AnalysisWindow> windows = new ArrayList<>(completedWindows(startedAt, endedAt));
        LocalDateTime residualStartAt = windows.isEmpty()
                ? startedAt
                : windows.getLast().endAt();
        if (residualStartAt.isBefore(endedAt)) {
            windows.add(new AnalysisWindow(residualStartAt, endedAt));
        }
        return List.copyOf(windows);
    }

    public record AnalysisWindow(LocalDateTime startAt, LocalDateTime endAt) {
    }
}
