package com.react.report.controller;

import com.react.ai.security.HostAuthentication;
import com.react.global.response.ApiResponse;
import com.react.report.dto.ReportReceipt;
import com.react.report.service.ReportRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/reports")
@RequiredArgsConstructor
public class ReportRequestController {
    private final ReportRequestService reports;
    private final HostAuthentication hosts;

    @PostMapping
    public ResponseEntity<ApiResponse<ReportReceipt>> request(@PathVariable long roomId, Authentication authentication) {
        var receipt = reports.request(roomId, hosts.requireHostEmail(authentication));
        return ResponseEntity.status(receipt.inProgress() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(ApiResponse.of(receipt));
    }
}
