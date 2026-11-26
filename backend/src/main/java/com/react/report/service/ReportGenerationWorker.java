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
    private final BackgroundReportGeneration background;

    public void check(long roomId) {
        reports.pending(roomId).ifPresent(report -> {
            if (report.invalidTime()) {
                reports.failInvalidTime(report.reportId());
            } else if (report.ready()) {
                if (background.handles(report.reportId())) {
                    reports.withPendingLock(report.reportId(), () -> background.start(roomId, report));
                } else {
                    reports.withClaim(report.reportId(), () -> generate(roomId, report));
                }
            }
        });
    }

    private void generate(long roomId, ReportGenerationRepository.Candidate report) {
        String content;
        try {
            content = mapper.writeValueAsString(overview.generate(roomId, report.email()));
        } catch (ReportGenerationException error) {
            failAiGeneration(report.reportId(), error);
            return;
        } catch (JsonProcessingException | RuntimeException error) {
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

    private void failAiGeneration(long reportId, ReportGenerationException error) {
        if (reports.failAiGeneration(reportId, error.reason().name(), error.diagnosticCode())) {
            log.warn("보고서 AI 생성 실패. reportId={}, reason={}, diagnosticCode={}", reportId, error.reason(), error.diagnosticCode());
        }
    }
}
