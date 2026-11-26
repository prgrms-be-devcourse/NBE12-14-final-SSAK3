package com.react.ai.dto;

import jakarta.validation.constraints.NotNull;

public record AiAnswerStatusRequest(@NotNull Boolean answered) {
}
