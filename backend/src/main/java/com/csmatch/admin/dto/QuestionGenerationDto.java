package com.csmatch.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public final class QuestionGenerationDto {
    private QuestionGenerationDto() { }

    /**
     * count 의 상한은 설정(app.llm.max-count)으로, model 의 허용 목록은 설정(app.llm.models)으로 정하므로
     * 서비스에서 검사한다. model 을 생략하면 기본 모델(app.llm.model)을 쓴다.
     */
    public record StartRequest(@NotNull @Positive Integer count, @Positive Integer categoryId,
                               @Size(max = 100) String model) { }

    public record Status(String status, String model, int requested, int succeeded, int failed,
                         OffsetDateTime startedAt, OffsetDateTime finishedAt) { }
}
