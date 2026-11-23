package com.react.report.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.report.repository.ReportGenerationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportGenerationWorker {
    private final ReportGenerationRepository reports;
    private final ReportOverviewService overview;
    private final ObjectMapper mapper;

    public void check(long roomId) {
        reports.pending(roomId).ifPresent(report -> {
            if (report.invalidTime()) {
                reports.failInvalidTime(report.reportId());
            } else if (report.ready()) {
                reports.withClaim(report.reportId(), () -> generate(roomId, report));
            }
        });
    }

    public void recover() {
        reports.failAbandonedJobs();
        for (long roomId : reports.pendingRoomIds()) {
            try {
                check(roomId);
            } catch (RuntimeException error) {
                log.error("보고서 복구 처리 중 오류가 발생했습니다. roomId={}, type={}", roomId, error.getClass().getSimpleName());
            }
        }
    }

    private void generate(long roomId, ReportGenerationRepository.Candidate report) {
        String content;
        try {
            content = mapper.writeValueAsString(overview.generate(roomId, report.email()));
        } catch (ReportGenerationException error) {
            reports.failGeneration(report.reportId(), error.reason().name());
            return;
        } catch (JsonProcessingException error) {
            reports.failGeneration(report.reportId(), ReportGenerationException.Reason.INVALID_REPORT_FORMAT.name());
            return;
        } catch (RuntimeException error) {
            log.error("보고서 본문을 생성하지 못했습니다. reportId={}, type={}", report.reportId(), error.getClass().getSimpleName());
            reports.failGeneration(report.reportId(), "REPORT_GENERATION_FAILED");
            return;
        }
        try {
            reports.complete(report.reportId(), content);
        } catch (DataAccessException error) {
            // 완료 UPDATE의 응답만 유실된 경우에도 저장된 COMPLETED는 유지한다.
            reports.failGeneration(report.reportId(), "REPORT_STORAGE_FAILED");
        }
    }
}
