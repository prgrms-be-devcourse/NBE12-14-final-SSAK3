package com.react.report.service;

import com.react.ai.event.AiAnalysisFinalized;
import com.react.report.event.ReportReadinessRequested;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.Executor;

@Component
public class ReportGenerationListener {
    private final ReportGenerationWorker worker;
    private final Executor executor;

    public ReportGenerationListener(ReportGenerationWorker worker,
                                    @Qualifier("reportGenerationExecutor") Executor executor) {
        this.worker = worker;
        this.executor = executor;
    }

    @TransactionalEventListener
    public void onReceipt(ReportReadinessRequested event) {
        executor.execute(() -> worker.check(event.roomId()));
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onFinalization(AiAnalysisFinalized event) {
        executor.execute(() -> worker.check(event.roomId()));
    }
}
