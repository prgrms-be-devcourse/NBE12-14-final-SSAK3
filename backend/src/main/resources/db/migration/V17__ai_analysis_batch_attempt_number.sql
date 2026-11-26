ALTER TABLE ai_analyses
    ADD COLUMN next_batch_attempt_number INT NOT NULL DEFAULT 1;
