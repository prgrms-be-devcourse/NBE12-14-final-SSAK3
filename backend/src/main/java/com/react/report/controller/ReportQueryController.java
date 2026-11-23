package com.react.report.controller;

import com.react.ai.security.HostAuthentication;
import com.react.global.response.ApiResponse;
import com.react.report.dto.ReportDetailView;
import com.react.report.dto.ReportStatusView;
import com.react.report.service.ReportQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/reports")
@RequiredArgsConstructor
public class ReportQueryController {
    private final ReportQueryService reports;
    private final HostAuthentication hosts;

    @GetMapping("/status")
    public ApiResponse<ReportStatusView> status(@PathVariable long roomId, Authentication authentication) {
        return ApiResponse.of(reports.status(roomId, hosts.requireHostEmail(authentication)));
    }

    @GetMapping
    public ApiResponse<ReportDetailView> detail(@PathVariable long roomId, Authentication authentication) {
        return ApiResponse.of(reports.detail(roomId, hosts.requireHostEmail(authentication)));
    }
}
