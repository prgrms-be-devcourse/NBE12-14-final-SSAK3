ALTER TABLE ai_analyses
    ADD COLUMN processing_started_at DATETIME NULL AFTER created_at,
    ADD KEY idx_ai_analyses_status_processing_started (status, processing_started_at);
