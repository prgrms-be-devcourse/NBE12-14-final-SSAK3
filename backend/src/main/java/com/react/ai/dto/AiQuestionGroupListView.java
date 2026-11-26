package com.react.ai.dto;

import java.util.List;

public record AiQuestionGroupListView(long roomId, List<AiQuestionGroupView> questionGroups, Long nextCursor) {
}
