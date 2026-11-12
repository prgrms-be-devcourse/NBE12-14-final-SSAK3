package com.csmatch.practice.service;

import com.csmatch.global.exception.BusinessException;
import com.csmatch.global.exception.ErrorCode;
import com.csmatch.practice.dto.PracticeDto;
import com.csmatch.practice.entity.*;
import com.csmatch.practice.repository.PracticeProgressRepository;
import com.csmatch.practice.repository.PracticeSessionRepository;
import com.csmatch.question.repository.CategoryRepository;
import com.csmatch.question.repository.QuestionRepository;
import com.csmatch.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
public class PracticeService {
    private final UserRepository users;
    private final PracticeProgressRepository progressRepository;
    private final PracticeSessionRepository sessions;
    private final QuestionRepository questions;
    private final CategoryRepository categories;

    // 사용자 행을 먼저 잠가 세션 생성·답안·초기화를 같은 순서로 직렬 처리한다.
    private void lockUser(Long userId) {
        users.findByIdForUpdate(userId).orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
    }

    private PracticeProgress progress(Long userId) {
        return progressRepository.findById(userId)
                .orElseGet(() -> progressRepository.save(new PracticeProgress(userId)));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }

    private void validateCategory(Integer categoryId) {
        if (categoryId != null && !categories.existsById(categoryId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    private Optional<PracticeSession> current(Long userId) {
        var current = sessions.findByUserIdAndStatus(userId, PracticeSession.Status.IN_PROGRESS.name());
        current.ifPresent(session -> session.reconcileHidden(now()));
        return current.filter(PracticeSession::isInProgress);
    }

    private PracticeSession owned(Long userId, Long sessionId) {
        PracticeSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        session.reconcileHidden(now());
        return session;
    }

    public record StartResult(PracticeDto.Session session, boolean created) { }

    public Optional<StartResult> start(Long userId, Integer categoryId) {
        lockUser(userId);
        validateCategory(categoryId);
        var existing = current(userId);
        if (existing.isPresent()) {
            if (!Objects.equals(existing.get().getCategoryId(), categoryId)) {
                throw new BusinessException(ErrorCode.PRACTICE_IN_PROGRESS);
            }
            return Optional.of(new StartResult(PracticeDto.Session.from(existing.get()), false));
        }
        int cycle = progress(userId).getLearningCycle();
        var selected = questions.findUnanswered(userId, cycle, categoryId, PageRequest.of(0, 10));
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        var session = sessions.save(new PracticeSession(userId, categoryId, cycle, selected, now()));
        return Optional.of(new StartResult(PracticeDto.Session.from(session), true));
    }

    public Optional<PracticeDto.Session> currentSession(Long userId) {
        lockUser(userId);
        return current(userId).map(PracticeDto.Session::from);
    }

    public Optional<PracticeDto.NextQuestion> next(Long userId, Long sessionId) {
        lockUser(userId);
        PracticeSession session = owned(userId, sessionId);
        if (!session.isInProgress()) {
            return Optional.empty();
        }
        return session.getAnswers().stream().filter(PracticeAnswer::isPending).findFirst()
                .map(answer -> new PracticeDto.NextQuestion(
                        (int) session.getAnswers().stream().filter(PracticeAnswer::isAnswered).count() + 1,
                        session.getQuestionCount(), PracticeDto.question(answer),
                        answer.getChoices().stream().map(PracticeDto.Choice::from).toList()));
    }

    // 숨김 정리와 완료 전이는 오류 응답이어도 커밋한다. 요청 오류는 데이터 변경 전에 판정한다.
    public record Submission(PracticeDto.AnswerResult result, ErrorCode error) { }

    public Submission submit(Long userId, Long sessionId, PracticeDto.AnswerRequest request) {
        lockUser(userId);
        PracticeSession session = owned(userId, sessionId);
        if (!session.isInProgress()) {
            return new Submission(null, ErrorCode.SESSION_FINISHED);
        }
        var answer = session.getAnswers().stream()
                .filter(item -> item.getQuestion().getId().equals(request.questionId())).findFirst();
        if (answer.isEmpty()) {
            return new Submission(null, ErrorCode.VALIDATION_FAILED);
        }
        if (answer.get().isAnswered()) {
            return new Submission(null, ErrorCode.ALREADY_ANSWERED);
        }
        if (answer.get().isSkipped()) {
            return new Submission(null, ErrorCode.QUESTION_UNAVAILABLE);
        }
        PracticeAnswer pending = session.getAnswers().stream().filter(PracticeAnswer::isPending).findFirst().orElseThrow();
        if (!pending.getId().equals(answer.get().getId())) {
            return new Submission(null, ErrorCode.VALIDATION_FAILED);
        }
        var choice = pending.getChoices().stream()
                .filter(value -> value.getChoiceId().equals(request.choiceId())).findFirst();
        if (choice.isEmpty()) {
            return new Submission(null, ErrorCode.VALIDATION_FAILED);
        }
        session.recordAnswer(pending, choice.get(), now());
        return new Submission(new PracticeDto.AnswerResult(Boolean.TRUE.equals(pending.getCorrect()),
                pending.correctChoiceId(), pending.getExplanation()), null);
    }

    public PracticeDto.Session detail(Long userId, Long sessionId) {
        lockUser(userId);
        return PracticeDto.Session.from(owned(userId, sessionId));
    }

    public List<PracticeDto.HistoryItem> history(Long userId, Long sessionId, boolean wrongOnly) {
        lockUser(userId);
        return owned(userId, sessionId).getAnswers().stream()
                .filter(answer -> !wrongOnly || Boolean.FALSE.equals(answer.getCorrect()))
                .map(PracticeDto.HistoryItem::from).toList();
    }

    public PracticeDto.Session abandon(Long userId, Long sessionId) {
        lockUser(userId);
        PracticeSession session = owned(userId, sessionId);
        if (session.isInProgress()) {
            session.abandon(now());
        }
        return PracticeDto.Session.from(session);
    }

    public PracticeDto.PageResult<PracticeDto.Session> list(Long userId, int page, int size) {
        lockUser(userId);
        current(userId);
        var result = sessions.findByUserIdOrderByStartedAtDescIdDesc(userId, page(page, size));
        return new PracticeDto.PageResult<>(result.stream().map(PracticeDto.Session::from).toList(),
                page, size, result.getTotalElements());
    }

    public PracticeDto.Progress getProgress(Long userId, Integer categoryId) {
        lockUser(userId);
        validateCategory(categoryId);
        return progressView(userId, categoryId);
    }

    private PracticeDto.Progress progressView(Long userId, Integer categoryId) {
        int cycle = progress(userId).getLearningCycle();
        Long currentId = current(userId).map(PracticeSession::getId).orElse(null);
        long total = questions.countAvailable(categoryId);
        long answered = questions.countAnswered(userId, cycle, categoryId);
        return new PracticeDto.Progress(cycle, total, answered, total - answered,
                total > 0 && total == answered, currentId);
    }

    public PracticeDto.Progress restart(Long userId) {
        lockUser(userId);
        if (current(userId).isPresent()) {
            throw new BusinessException(ErrorCode.PRACTICE_IN_PROGRESS);
        }
        progress(userId).restart();
        return progressView(userId, null);
    }

    @Transactional(readOnly = true)
    public List<PracticeDto.Category> categories() {
        return categories.findAll(Sort.by("id")).stream()
                .map(category -> new PracticeDto.Category(category.getId(), category.getName())).toList();
    }

    public static PageRequest page(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return PageRequest.of(page, size);
    }
}
