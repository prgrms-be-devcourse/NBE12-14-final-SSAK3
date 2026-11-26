ALTER TABLE ai_topic_groups
    ADD COLUMN answered_at DATETIME NULL,
    ADD COLUMN answered_by_user_id BIGINT NULL,
    ADD CONSTRAINT fk_ai_topic_groups_answered_by FOREIGN KEY (answered_by_user_id) REFERENCES users (id),
    ADD INDEX idx_ai_topic_groups_answered_at (answered_at);
