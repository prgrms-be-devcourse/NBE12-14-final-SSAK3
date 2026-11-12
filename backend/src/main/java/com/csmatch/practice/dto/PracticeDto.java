package com.csmatch.practice.dto;

import com.csmatch.practice.entity.PracticeAnswer;
import com.csmatch.practice.entity.PracticeChoice;
import com.csmatch.practice.entity.PracticeSession;
import com.csmatch.question.Enum.QuestionStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

public final class PracticeDto {
    private PracticeDto() { }

    public record StartRequest(@Positive Integer categoryId) { }
    public record AnswerRequest(@NotNull @Positive Long questionId, @NotNull @Positive Long choiceId) { }
    public record Choice(Long choiceId, int choiceNo, String content) {
        public static Choice from(PracticeChoice choice) {
            return new Choice(choice.getChoiceId(), choice.getChoiceNo(), choice.getContent());
        }
    }
    public record Question(Long questionId, Integer categoryId, String difficulty, String content) { }
    public record NextQuestion(int index, int total, Question question, List<Choice> choices) { }
    public record AnswerResult(boolean correct, Long correctChoiceId, String explanation) { }
    public record Session(Long sessionId, Integer categoryId, int learningCycle, int questionCount,
                          int answeredCount, int correctCount, String status,
                          OffsetDateTime startedAt, OffsetDateTime endedAt) {
        public static Session from(PracticeSession session) {
            return new Session(session.getId(), session.getCategoryId(), session.getLearningCycle(),
                    session.getQuestionCount(), (int) session.getAnswers().stream().filter(PracticeAnswer::isAnswered).count(),
                    session.getCorrectCount(), session.getStatus(), time(session.getStartedAt()), time(session.getEndedAt()));
        }
    }
    public record HistoryItem(int index, Question question, List<Choice> choices, String status,
                              boolean hidden, Long selectedChoiceId, Boolean correct, Long correctChoiceId,
                              String explanation, OffsetDateTime submittedAt) {
        public static HistoryItem from(PracticeAnswer answer) {
            boolean answered = answer.isAnswered();
            return new HistoryItem(answer.getQuestionIndex(), PracticeDto.question(answer),
                    answer.getChoices().stream().map(Choice::from).toList(),
                    answered ? "ANSWERED" : answer.isSkipped() ? "SKIPPED" : "UNANSWERED",
                    answer.getQuestion().getStatus() != QuestionStatus.ACTIVE,
                    answer.getSelectedChoiceId(), answer.getCorrect(),
                    answered ? answer.correctChoiceId() : null,
                    answered ? answer.getExplanation() : null, time(answer.getSubmittedAt()));
        }
    }
    public record Progress(int learningCycle, long totalCount, long answeredCount, long remainingCount,
                           boolean completed, Long currentSessionId) { }
    public record Category(Integer categoryId, String name) { }
    public record PageResult<T>(List<T> items, int page, int size, long totalElements) { }

    public static Question question(PracticeAnswer answer) {
        return new Question(answer.getQuestion().getId(), answer.getCategoryId(), answer.getDifficulty(), answer.getContent());
    }

    public static OffsetDateTime time(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.ofHours(9));
    }
}
