package com.csmatch.review.dto;

import com.csmatch.practice.dto.PracticeDto;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.OffsetDateTime;
import java.util.List;

public final class ReviewDto {
    private ReviewDto() { }

    public record SaveRequest(@NotNull @Positive Long questionId, @Positive Long matchId,
                              @Positive Long practiceSessionId) { }
    public record Saved(Long questionId, OffsetDateTime savedAt) { }
    public record Item(Long questionId, Integer categoryId, String difficulty, boolean available,
                       String content, List<PracticeDto.Choice> choices, Long correctChoiceId,
                       String correctChoiceContent, String explanation, OffsetDateTime savedAt) { }
}
