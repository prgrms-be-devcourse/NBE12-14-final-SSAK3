package com.react.report.service;

import com.react.report.repository.ReportAttemptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportResponseRecovery {
    private static final int BATCH_SIZE = 16;
    private final ReportAttemptRepository attempts;
    private final BackgroundReportGeneration generation;
    private final BackgroundReportClient client;

    public void receiveWebhooks() {
        process(attempts.dueWebhooks(BATCH_SIZE), id -> {
            try {
                generation.receiveWebhook(id);
            } catch (BackgroundReportClient.NotFound expired) {
                // If we already know the attempt, its regular poll handles expiry.
            }
            attempts.finishWebhook(id);
        }, attempts::deferWebhook);
    }

    public void pollResponses() {
        process(attempts.dueResponses(BATCH_SIZE), generation::poll, attempts::deferResponse);
    }

    public void deleteResponses() {
        process(attempts.dueDeletes(BATCH_SIZE), id -> {
            try {
                client.delete(id);
            } catch (BackgroundReportClient.NotFound alreadyDeleted) {
                // The previous DELETE may have succeeded before our process stopped.
            }
            attempts.finishDelete(id);
        }, attempts::deferDelete);
    }

    private void process(List<String> ids, Consumer<String> action, Consumer<String> defer) {
        for (var id : ids) {
            try {
                action.accept(id);
            } catch (RuntimeException failure) {
                log.warn("보고서 외부 응답 처리 재시도 예정. type={}", failure.getClass().getSimpleName());
                defer.accept(id);
            }
        }
    }
}
