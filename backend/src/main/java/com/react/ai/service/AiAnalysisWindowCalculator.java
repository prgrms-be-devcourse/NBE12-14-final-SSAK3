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

    public record AnalysisWindow(LocalDateTime startAt, LocalDateTime endAt) {
    }
}
