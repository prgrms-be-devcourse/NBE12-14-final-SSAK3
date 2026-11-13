package com.csmatch.admin.service;

import com.csmatch.admin.dto.QuestionGenerationDto;
import com.csmatch.question.llm.QuestionGenerator;
import com.csmatch.question.repository.CategoryRepository;
import com.csmatch.question.repository.ConceptRepository;
import com.csmatch.question.repository.QuestionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class QuestionGenerationServiceTest {

    @Test
    void 실행한_적이_없으면_IDLE이다() {
        QuestionGenerationService service = new QuestionGenerationService(mock(QuestionGenerator.class),
                mock(ConceptRepository.class), mock(CategoryRepository.class), mock(QuestionRepository.class),
                mock(GeneratedQuestionSaver.class), List.of("claude-sonnet-5-5"), 30, 3);

        QuestionGenerationDto.Status status = service.status();

        assertThat(status.status()).isEqualTo("IDLE");
        assertThat(status.model()).isNull();
        assertThat(status.requested()).isZero();
        assertThat(status.startedAt()).isNull();
        assertThat(status.finishedAt()).isNull();
        service.shutdown();
    }
}
