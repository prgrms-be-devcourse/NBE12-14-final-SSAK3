package com.react.report.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.report.dto.ReportDetailView;
import com.react.report.dto.ReportReceipt;
import com.react.report.dto.ReportStatusView;
import com.react.report.repository.ReportQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportQueryService {
    private static final ZoneOffset STORAGE_OFFSET = ZoneOffset.ofHours(9);

    private final ReportRoomAccess rooms;
    private final ReportQueryRepository reports;
    private final ObjectMapper mapper;

    public ReportStatusView status(long roomId, String hostEmail) {
        rooms.requireOwnedEndedRoom(roomId, hostEmail);
        return reports.findStatus(roomId).orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
    }

    public ReportDetailView detail(long roomId, String hostEmail) {
        rooms.requireOwnedEndedRoom(roomId, hostEmail);
        var row = reports.findDetail(roomId).orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
        var createdAt = row.createdAt().toInstant(STORAGE_OFFSET);
        if (row.status() != ReportReceipt.Status.COMPLETED) {
            return new ReportDetailView(row.reportId(), roomId, row.status(), createdAt, null, null,
                    ReportStatusView.FailureCategory.from(row.status(), row.errorMessage()));
        }
        return new ReportDetailView(row.reportId(), roomId, row.status(), createdAt,
                row.generatedAt().toInstant(STORAGE_OFFSET), storedContent(row.content()), null);
    }

    private JsonNode storedContent(String content) {
        try {
            return mapper.readTree(content);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
