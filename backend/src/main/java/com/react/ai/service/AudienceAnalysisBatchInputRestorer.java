package com.react.ai.service;

import com.react.ai.dto.AudienceAnalysisBatch;
import com.react.ai.dto.AudienceAnalysisBatchManifest;
import com.react.ai.dto.AudienceMessage;
import com.react.ai.dto.PreprocessedAudienceMessages;
import com.react.ai.exception.InvalidStoredAiBatchDataException;
import com.react.ai.repository.AiAnalysisBatchRepository;
import com.react.ai.repository.AiAnalysisRepository;
import com.react.ai.repository.AudienceMessageRepository;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 저장된 ID 목록에 해당하는 같은 방·구간의 원문만 읽고 전처리 결과의 지문을 재검증한다. */
@Component
public class AudienceAnalysisBatchInputRestorer {
    private final AudienceMessageRepository messages;
    private final AudienceMessagePreprocessor preprocessor;

    public AudienceAnalysisBatchInputRestorer(AudienceMessageRepository messages,
                                              AudienceMessagePreprocessor preprocessor) {
        this.messages = messages;
        this.preprocessor = preprocessor;
    }

    public AudienceAnalysisBatch restore(AiAnalysisRepository.Analysis analysis,
                                         AiAnalysisBatchRepository.Batch stored) {
        AudienceAnalysisBatchManifest manifest = stored.inputManifest();
        Set<Long> ids = new HashSet<>();
        for (var message : manifest.messages()) {
            if (message.originalMessageIds().isEmpty() || message.occurrenceCount() != message.originalMessageIds().size()
                    || !message.originalMessageIds().contains(message.representativeMessageId())) {
                throw mismatch();
            }
            for (long id : message.originalMessageIds()) {
                if (id <= 0 || !ids.add(id)) {
                    throw mismatch();
                }
            }
        }
        List<AudienceMessage> originalMessages = messages.findAudienceMessages(analysis.roomId(),
                analysis.windowStartAt(), analysis.windowEndAt()).stream()
                .filter(message -> ids.contains(message.messageId())).toList();
        if (originalMessages.size() != ids.size()) {
            throw mismatch();
        }
        PreprocessedAudienceMessages restored = preprocessor.preprocess(originalMessages);
        AudienceAnalysisBatch batch = new AudienceAnalysisBatch(stored.batchIndex(), restored.inputMessages(),
                restored.originalMessageIdsByRepresentative());
        if (batch.inputMessages().size() != stored.inputMessageCount()
                || batch.inputCharacterCount() != stored.inputCharacterCount()
                || !AudienceAnalysisBatchManifest.from(batch).equals(manifest)) {
            throw mismatch();
        }
        return batch;
    }

    private InvalidStoredAiBatchDataException mismatch() {
        return InvalidStoredAiBatchDataException.manifestMismatch();
    }
}
