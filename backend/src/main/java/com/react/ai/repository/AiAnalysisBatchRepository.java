package com.react.ai.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.ai.domain.AiAnalysisBatchStatus;
import com.react.ai.domain.AiAnalysisStatus;
import com.react.ai.dto.AiAnalysisResult;
import com.react.ai.dto.AudienceAnalysisBatch;
import com.react.ai.dto.AudienceAnalysisBatchManifest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class AiAnalysisBatchRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    /** 배치 목록 전체를 한 번에 확정한다. UNIQUE 충돌 시 어느 입력도 덮어쓰지 않는다. */
    @Transactional
    public void createPending(long analysisId, List<AudienceAnalysisBatch> batches) {
        insertPending(analysisId, batches);
    }

    /**
     * 배치 manifest와 부모 PROCESSING 전환을 같은 짧은 트랜잭션으로 확정한다.
     * 부모 행 잠금으로 stale recovery와 경쟁해도 FAILED 부모 아래에 배치가 남지 않게 한다.
     */
    @Transactional
    public Initialization initializeForProcessing(long analysisId, List<AudienceAnalysisBatch> pendingBatches) {
        AiAnalysisStatus parentStatus = jdbc.sql("SELECT status FROM ai_analyses WHERE id = ? FOR UPDATE")
                .param(analysisId).query(String.class).optional().map(AiAnalysisStatus::valueOf)
                .orElseThrow(() -> new IllegalArgumentException("AI 분석을 찾을 수 없습니다."));
        if (parentStatus == AiAnalysisStatus.COMPLETED || parentStatus == AiAnalysisStatus.FAILED) {
            return new Initialization(parentStatus, false);
        }
        if (!existsForAnalysis(analysisId)) {
            insertPending(analysisId, pendingBatches);
        }
        int updated = jdbc.sql("""
                UPDATE ai_analyses
                SET status = 'PROCESSING', processing_started_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status IN ('PENDING', 'PROCESSING')
                """).param(analysisId).update();
        if (updated != 1) {
            throw new IllegalStateException("AI 배치 분석을 처리 상태로 변경할 수 없습니다.");
        }
        return new Initialization(AiAnalysisStatus.PROCESSING, true);
    }

    private void insertPending(long analysisId, List<AudienceAnalysisBatch> batches) {
        if (analysisId <= 0 || batches == null || batches.isEmpty()) {
            throw new IllegalArgumentException("저장할 AI 배치가 없습니다.");
        }
        for (int index = 0; index < batches.size(); index++) {
            AudienceAnalysisBatch batch = batches.get(index);
            if (batch.batchIndex() != index || batch.inputMessages().isEmpty()) {
                throw new IllegalArgumentException("AI 배치 인덱스 또는 입력이 올바르지 않습니다.");
            }
            jdbc.sql("""
                    INSERT INTO ai_analysis_batches(
                        analysis_id, batch_index, status, input_manifest, input_message_count,
                        input_character_count, created_at, updated_at
                    ) VALUES (?, ?, 'PENDING', ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """).params(analysisId, index, toJson(AudienceAnalysisBatchManifest.from(batch)),
                    batch.inputMessages().size(), batch.inputCharacterCount()).update();
        }
    }

    public List<Batch> findByAnalysis(long analysisId) {
        return jdbc.sql("""
                SELECT id, analysis_id, batch_index, status, input_manifest, input_message_count,
                       input_character_count, result_json, model_name, claim_token, lease_until,
                       error_code, created_at, updated_at, completed_at
                FROM ai_analysis_batches WHERE analysis_id = ? ORDER BY batch_index
                """).param(analysisId).query((rs, rowNum) -> new Batch(
                rs.getLong("id"), rs.getLong("analysis_id"), rs.getInt("batch_index"),
                AiAnalysisBatchStatus.valueOf(rs.getString("status")),
                fromJson(rs.getString("input_manifest"), AudienceAnalysisBatchManifest.class),
                rs.getInt("input_message_count"), rs.getInt("input_character_count"),
                rs.getString("result_json") == null ? null : fromJson(rs.getString("result_json"), AiAnalysisResult.class),
                rs.getString("model_name"), rs.getString("claim_token"),
                rs.getObject("lease_until", LocalDateTime.class), rs.getString("error_code"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getObject("completed_at", LocalDateTime.class))).list();
    }

    public boolean existsForAnalysis(long analysisId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM ai_analysis_batches WHERE analysis_id = ?)")
                .param(analysisId).query(Boolean.class).single();
    }

    public String findResultJson(long batchId) {
        return jdbc.sql("SELECT result_json FROM ai_analysis_batches WHERE id = ? AND status = 'COMPLETED'")
                .param(batchId).query(String.class).single();
    }

    public boolean claim(long batchId, UUID token, Duration leaseDuration) {
        if (token == null || leaseDuration == null || leaseDuration.toSeconds() < 1) {
            throw new IllegalArgumentException("AI 배치 lease가 올바르지 않습니다.");
        }
        return jdbc.sql("""
                UPDATE ai_analysis_batches
                SET status = 'PROCESSING', claim_token = ?,
                    lease_until = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL ? SECOND), updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND EXISTS (
                    SELECT 1 FROM ai_analyses a
                    WHERE a.id = ai_analysis_batches.analysis_id AND a.status = 'PROCESSING'
                ) AND (status = 'PENDING'
                    OR (status = 'PROCESSING' AND lease_until < CURRENT_TIMESTAMP))
                """).params(token.toString(), leaseDuration.toSeconds(), batchId).update() == 1;
    }

    public boolean isClaimCurrent(long batchId, UUID token) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM ai_analysis_batches b JOIN ai_analyses a ON a.id = b.analysis_id
                    WHERE b.id = ? AND b.status = 'PROCESSING' AND b.claim_token = ?
                      AND b.lease_until > CURRENT_TIMESTAMP AND a.status = 'PROCESSING'
                )
                """).params(batchId, token.toString()).query(Boolean.class).single();
    }

    /** 호출자가 기존 JSON 파서와 배치 대표 ID 검증을 마친 결과만 전달한다. */
    public boolean saveValidatedResult(long batchId, UUID token, AiAnalysisResult validatedResult,
                                       String modelName) {
        if (token == null || validatedResult == null || modelName == null || modelName.isBlank()
                || modelName.length() > 100) {
            throw new IllegalArgumentException("AI 배치 완료 결과가 올바르지 않습니다.");
        }
        return jdbc.sql("""
                UPDATE ai_analysis_batches
                SET status = 'COMPLETED', result_json = ?, model_name = ?, error_code = NULL,
                    claim_token = NULL, lease_until = NULL, completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?
                  AND lease_until > CURRENT_TIMESTAMP
                  AND EXISTS (SELECT 1 FROM ai_analyses a
                      WHERE a.id = ai_analysis_batches.analysis_id AND a.status = 'PROCESSING')
                """).params(toJson(validatedResult), modelName, batchId, token.toString()).update() == 1;
    }

    public boolean markFailed(long batchId, UUID token, String errorCode) {
        if (token == null || errorCode == null || errorCode.isBlank() || errorCode.length() > 50) {
            throw new IllegalArgumentException("AI 배치 오류 코드가 올바르지 않습니다.");
        }
        return jdbc.sql("""
                UPDATE ai_analysis_batches
                SET status = 'FAILED', error_code = ?, claim_token = NULL, lease_until = NULL,
                    completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?
                  AND lease_until > CURRENT_TIMESTAMP
                  AND EXISTS (SELECT 1 FROM ai_analyses a
                      WHERE a.id = ai_analysis_batches.analysis_id AND a.status = 'PROCESSING')
                """).params(errorCode, batchId, token.toString()).update() == 1;
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("AI 배치 데이터를 직렬화할 수 없습니다.", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 AI 배치 데이터를 읽을 수 없습니다.", e);
        }
    }

    public record Batch(long id, long analysisId, int batchIndex, AiAnalysisBatchStatus status,
                        AudienceAnalysisBatchManifest inputManifest, int inputMessageCount,
                        int inputCharacterCount, AiAnalysisResult result, String modelName,
                        String claimToken, LocalDateTime leaseUntil, String errorCode,
                        LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime completedAt) {
    }

    public record Initialization(AiAnalysisStatus status, boolean initialized) {
    }
}
