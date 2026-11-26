package com.react.report.service;

import com.react.ai.event.AiAnalysisFinalized;
import com.react.report.event.ReportReadinessRequested;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Component
public class ReportGenerationListener {
    private final ReportGenerationWorker worker;
    private final Executor executor;
    private final Set<Long> recoveryChecks = ConcurrentHashMap.newKeySet();

    public ReportGenerationListener(ReportGenerationWorker worker,
                                    @Qualifier("reportGenerationExecutor") Executor executor) {
        this.worker = worker;
        this.executor = executor;
    }

    @TransactionalEventListener
    public void onReceipt(ReportReadinessRequested event) {
        submit(event.roomId(), () -> worker.check(event.roomId()));
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onFinalization(AiAnalysisFinalized event) {
        submit(event.roomId(), () -> worker.check(event.roomId()));
    }

    public boolean submitRecovery(long roomId) {
        if (!recoveryChecks.add(roomId)) return true;
        boolean accepted = submit(roomId, () -> {
            try {
                worker.check(roomId);
            } finally {
                recoveryChecks.remove(roomId);
            }
        });
        if (!accepted) recoveryChecks.remove(roomId);
        return accepted;
    }

    private boolean submit(long roomId, Runnable check) {
        try {
            executor.execute(check);
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("보고서 생성 작업 풀이 가득 차 다음 복구 주기에서 다시 확인합니다. roomId={}", roomId);
            return false;
        }
    }
}
