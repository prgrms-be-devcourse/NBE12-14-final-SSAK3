package com.react.report.service;

import com.react.report.repository.ReportGenerationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportRecoveryService {
    private static final int BATCH_SIZE = 32;
    private final ReportGenerationRepository reports;
    private final ReportRequestService requests;
    private final ReportGenerationListener generation;

    public void recover() {
        for (long roomId : reports.roomsAwaitingReceipt(BATCH_SIZE)) {
            try {
                requests.recoverReceipt(roomId);
            } catch (RuntimeException error) {
                log.warn("보고서 접수 복구 실패. roomId={}, type={}", roomId, error.getClass().getSimpleName());
            }
        }
        reports.failAbandonedJobs(BATCH_SIZE);
        for (long roomId : reports.readyRoomIds(BATCH_SIZE)) {
            if (!generation.submitRecovery(roomId)) break;
        }
    }
}
