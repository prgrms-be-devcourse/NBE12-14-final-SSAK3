package com.csmatch.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.OffsetDateTime;

public final class QuestionGenerationDto {
    private QuestionGenerationDto() { }

    /** count 의 상한은 설정(app.llm.max-count)으로 정하므로 서비스에서 검사한다. */
    public record StartRequest(@NotNull @Positive Integer count, @Positive Integer categoryId) { }

    public record Status(String status, int requested, int succeeded, int failed,
                         OffsetDateTime startedAt, OffsetDateTime finishedAt) { }
}
