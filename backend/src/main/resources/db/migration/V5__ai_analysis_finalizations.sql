CREATE TABLE ai_analysis_finalizations (
    lecture_room_id       BIGINT       NOT NULL,
    started_at            DATETIME     NOT NULL,
    ended_at              DATETIME     NOT NULL,
    status                VARCHAR(20)  NOT NULL COMMENT 'PENDING, PROCESSING, COMPLETED, FAILED',
    claim_token           VARCHAR(36)  NULL,
    lease_until           DATETIME     NULL,
    failed_analysis_count INT          NOT NULL DEFAULT 0,
    last_error            VARCHAR(500) NULL,
    created_at            DATETIME     NOT NULL,
    updated_at            DATETIME     NOT NULL,
    completed_at          DATETIME     NULL,
    PRIMARY KEY (lecture_room_id),
    KEY idx_ai_analysis_finalizations_status_lease (status, lease_until),
    KEY idx_ai_analysis_finalizations_ended_at (ended_at),
    CONSTRAINT fk_ai_analysis_finalizations_room
        FOREIGN KEY (lecture_room_id) REFERENCES lecture_rooms (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
