package com.react.feed.controller;

import com.react.feed.dto.UnderstandingSummaryView;
import com.react.feed.service.UnderstandingService;
import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/understanding")
@RequiredArgsConstructor
public class UnderstandingController {
    private final UnderstandingService understandings;

    // TODO(SCRUM-164): 청중 인증이 확정되면 PUT·GET /me 를 추가한다. 지금은 참여자를 서버에서 확인할 수단이 없다.
    @GetMapping("/summary")
    public ApiResponse<UnderstandingSummaryView> getSummary(@PathVariable long roomId, Authentication authentication) {
        return ApiResponse.of(understandings.getSummary(roomId, email(authentication)));
    }

    private String email(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        boolean isHost = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_HOST".equals(authority.getAuthority()));
        if (!isHost) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return authentication.getName();
    }
}
