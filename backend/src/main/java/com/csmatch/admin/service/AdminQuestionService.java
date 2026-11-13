package com.csmatch.admin.service;

import com.csmatch.admin.dto.AdminQuestionDto;
import com.csmatch.global.exception.BusinessException;
import com.csmatch.global.exception.ErrorCode;
import com.csmatch.question.Enum.QuestionSource;
import com.csmatch.question.entity.Category;
import com.csmatch.question.entity.Question;
import com.csmatch.question.repository.CategoryRepository;
import com.csmatch.question.repository.QuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminQuestionService {
    private final QuestionRepository questions;
    private final CategoryRepository categories;

    /** 수동 등록 문제는 항상 DRAFT 로 저장한다. 출제 풀에는 상태 변경(승인)을 거쳐야 들어간다. */
    @Transactional
    public AdminQuestionDto.CreateResponse create(Long adminId, AdminQuestionDto.CreateRequest request) {
        validateChoices(request.choices());
        Category category = categories.findById(request.categoryId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));

        List<Question.ChoiceInput> inputs = request.choices().stream()
                .sorted(Comparator.comparing(AdminQuestionDto.ChoiceRequest::choiceNo))
                .map(choice -> new Question.ChoiceInput(choice.content(), choice.isCorrect()))
                .toList();

        Question question = Question.create(category, request.difficulty(), request.content(),
                request.explanation(), QuestionSource.MANUAL, null, adminId, inputs);
        return AdminQuestionDto.CreateResponse.from(questions.save(question));
    }

    /** 보기 번호는 1~4 가 한 번씩, 정답은 정확히 1개여야 한다. */
    private void validateChoices(List<AdminQuestionDto.ChoiceRequest> choices) {
        Set<Integer> numbers = choices.stream()
                .map(AdminQuestionDto.ChoiceRequest::choiceNo)
                .collect(Collectors.toSet());
        long correctCount = choices.stream().filter(AdminQuestionDto.ChoiceRequest::isCorrect).count();
        if (!numbers.equals(Set.of(1, 2, 3, 4)) || correctCount != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
