package com.csmatch.admin.dto;

import com.csmatch.question.Enum.Difficulty;
import com.csmatch.question.Enum.QuestionStatus;
import com.csmatch.question.entity.Question;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class AdminQuestionDto {
    private AdminQuestionDto() { }

    public record ChoiceRequest(
            @NotNull @Min(1) @Max(4) Integer choiceNo,
            @NotBlank @Size(max = 500) String content,
            @NotNull Boolean isCorrect) { }

    public record CreateRequest(
            @NotNull @Positive Integer categoryId,
            @NotNull Difficulty difficulty,
            @NotBlank String content,
            @NotNull @Size(min = 4, max = 4) List<@NotNull @Valid ChoiceRequest> choices,
            @NotBlank String explanation) { }

    public record CreateResponse(Long questionId, QuestionStatus status) {
        public static CreateResponse from(Question question) {
            return new CreateResponse(question.getId(), question.getStatus());
        }
    }
}
