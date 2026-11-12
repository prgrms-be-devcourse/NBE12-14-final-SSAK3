package com.csmatch.review.service;

import com.csmatch.global.exception.BusinessException;
import com.csmatch.global.exception.ErrorCode;
import com.csmatch.practice.dto.PracticeDto;
import com.csmatch.practice.repository.PracticeSessionRepository;
import com.csmatch.practice.service.PracticeService;
import com.csmatch.question.Enum.QuestionStatus;
import com.csmatch.question.entity.QuestionChoice;
import com.csmatch.question.repository.QuestionRepository;
import com.csmatch.review.dto.ReviewDto;
import com.csmatch.review.entity.SavedQuestion;
import com.csmatch.review.repository.SavedQuestionRepository;
import com.csmatch.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class ReviewService {
    private final SavedQuestionRepository saved;
    private final PracticeSessionRepository sessions;
    private final QuestionRepository questions;
    private final UserRepository users;
    private final JdbcTemplate jdbc;

    public ReviewDto.Saved save(Long userId, ReviewDto.SaveRequest request) {
        users.findByIdForUpdate(userId).orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
        if ((request.matchId() == null) == (request.practiceSessionId() == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (request.practiceSessionId() != null) {
            var session = sessions.findById(request.practiceSessionId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
            if (!session.getUserId().equals(userId) || session.getAnswers().stream().noneMatch(answer ->
                    answer.getQuestion().getId().equals(request.questionId()) && answer.isAnswered())) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
        } else {
            // 대결 엔티티 구현과 무관하게 기존 matchId 계약의 참가·출제 여부를 검증한다.
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM match_players p
                    JOIN match_rounds r ON r.match_id = p.match_id
                    WHERE p.user_id = ? AND p.match_id = ? AND r.question_id = ?
                    """, Integer.class, userId, request.matchId(), request.questionId());
            if (count == null || count == 0) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
        }
        var question = questions.findById(request.questionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (question.getStatus() != QuestionStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.QUESTION_UNAVAILABLE);
        }
        var key = new SavedQuestion.Key(userId, request.questionId());
        if (saved.existsById(key)) {
            throw new BusinessException(ErrorCode.ALREADY_SAVED);
        }
        var item = saved.save(new SavedQuestion(userId, question, request.matchId(), request.practiceSessionId(),
                LocalDateTime.now(ZoneId.of("Asia/Seoul"))));
        return new ReviewDto.Saved(item.getQuestionId(), PracticeDto.time(item.getSavedAt()));
    }

    @Transactional(readOnly = true)
    public PracticeDto.PageResult<ReviewDto.Item> list(Long userId, Integer categoryId, int page, int size) {
        var result = saved.findSaved(userId, categoryId, PracticeService.page(page, size));
        return new PracticeDto.PageResult<>(result.stream().map(this::view).toList(), page, size, result.getTotalElements());
    }

    private ReviewDto.Item view(SavedQuestion item) {
        var question = item.getQuestion();
        boolean available = question.getStatus() == QuestionStatus.ACTIVE;
        var correct = available ? question.getChoices().stream().filter(QuestionChoice::isCorrect).findFirst().orElseThrow() : null;
        return new ReviewDto.Item(question.getId(), question.getCategory().getId(), question.getDifficulty().name(), available,
                available ? question.getContent() : null,
                available ? question.getChoices().stream().map(choice -> new PracticeDto.Choice(
                        choice.getId(), choice.getChoiceNo(), choice.getContent())).toList() : List.of(),
                correct == null ? null : correct.getId(), correct == null ? null : correct.getContent(),
                available ? question.getExplanation() : null, PracticeDto.time(item.getSavedAt()));
    }
}
