package com.react.ai.controller;

import com.react.ai.dto.AiAnalysisListView;
import com.react.ai.dto.AiTopicGroupMessagesView;
import com.react.ai.security.HostAuthentication;
import com.react.ai.service.AiAnalysisQueryService;
import com.react.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/ai")
@RequiredArgsConstructor
public class AiAnalysisQueryController {
    private final AiAnalysisQueryService queries;
    private final HostAuthentication hostAuthentication;

    @GetMapping("/analyses")
    public ApiResponse<AiAnalysisListView> getAnalyses(@PathVariable long roomId,
                                                        @RequestParam(defaultValue = "10") int limit,
                                                        Authentication authentication) {
        return ApiResponse.of(queries.getAnalyses(roomId, hostAuthentication.requireHostEmail(authentication), limit));
    }

    @GetMapping("/topic-groups/{topicGroupId}/messages")
    public ApiResponse<AiTopicGroupMessagesView> getTopicGroupMessages(@PathVariable long roomId,
                                                                         @PathVariable long topicGroupId,
                                                                         Authentication authentication) {
        return ApiResponse.of(queries.getTopicGroupMessages(
                roomId, topicGroupId, hostAuthentication.requireHostEmail(authentication)));
    }
}
