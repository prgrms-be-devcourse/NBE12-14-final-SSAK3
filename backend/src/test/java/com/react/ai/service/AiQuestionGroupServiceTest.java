package com.react.ai.service;

import com.react.ai.repository.AiQuestionGroupRepository;
import com.react.ai.domain.AiAnalysisStatus;
import com.react.global.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AiQuestionGroupServiceTest {
    private final AiQuestionGroupRepository repository = mock(AiQuestionGroupRepository.class);
    private final AiQuestionGroupService service = new AiQuestionGroupService(repository);

    @Test
    void 질문_외_유형은_상태를_변경하지_않는다() {
        when(repository.findOwner(10)).thenReturn(Optional.of(new AiQuestionGroupRepository.Owner(1, "host@example.com")));
        when(repository.findGroup(10, 20)).thenReturn(Optional.of(new AiQuestionGroupRepository.QuestionGroup(
                20, 30, "의견", "요약", "OPINION", AiAnalysisStatus.COMPLETED, null, LocalDateTime.now(), 1)));
        assertThatThrownBy(() -> service.change(10, 20, "host@example.com", true))
                .isInstanceOf(BusinessException.class);
        verify(repository, never()).changeAnswerStatus(anyLong(), anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void 완료되지_않은_분석의_질문은_상태를_변경하지_않는다() {
        when(repository.findOwner(10)).thenReturn(Optional.of(new AiQuestionGroupRepository.Owner(1, "host@example.com")));
        when(repository.findGroup(10, 20)).thenReturn(Optional.of(new AiQuestionGroupRepository.QuestionGroup(
                20, 30, "질문", "요약", "QUESTION", AiAnalysisStatus.PROCESSING, null, LocalDateTime.now(), 1)));
        assertThatThrownBy(() -> service.change(10, 20, "host@example.com", true))
                .isInstanceOf(BusinessException.class);
        verify(repository, never()).changeAnswerStatus(anyLong(), anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void 목록_제한과_필터를_검증한다() {
        when(repository.findOwner(10)).thenReturn(Optional.of(new AiQuestionGroupRepository.Owner(1, "host@example.com")));
        assertThatThrownBy(() -> service.list(10, "host@example.com", "OTHER", 20, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.list(10, "host@example.com", "UNANSWERED", 51, null))
                .isInstanceOf(BusinessException.class);
        assertThat(service.list(10, "host@example.com", "UNANSWERED", 20, null).questionGroups()).isEmpty();
        verify(repository).findQuestions(10, false, null, 21);
    }
}
