-- 백그라운드 생성 시도 한 건은 최초 요청 또는 수동 재시도 한 차례에 대응한다.
CREATE TABLE report_generation_attempts (
    attempt_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_id BIGINT NOT NULL,
    retry_used BOOLEAN NOT NULL,
    state VARCHAR(20) NOT NULL,
    response_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    input_json JSON NULL,
    failure_alerted BOOLEAN NOT NULL DEFAULT FALSE,
    next_action_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attempt_key),
    UNIQUE KEY uk_report_attempt_ordinal (report_id, retry_used),
    UNIQUE KEY uk_report_attempt_response (response_id),
    KEY idx_report_attempt_due (state, next_action_at, response_id),
    CONSTRAINT chk_report_attempt_state CHECK (
        (state IN ('SUBMITTING','UNKNOWN') AND input_json IS NOT NULL AND response_id IS NULL)
        OR (state='REMOTE' AND input_json IS NOT NULL AND response_id IS NOT NULL)
        OR (state='EXPIRED' AND input_json IS NULL AND response_id IS NULL)
        OR (state='DELETE_PENDING' AND input_json IS NULL AND response_id IS NOT NULL)
        OR (state='FINISHED' AND input_json IS NULL)
    ),
    CONSTRAINT fk_report_attempt_report FOREIGN KEY (report_id) REFERENCES lecture_reports(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE report_generation_slots (
    slot_id TINYINT NOT NULL,
    attempt_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (slot_id),
    UNIQUE KEY uk_report_slot_attempt (attempt_key),
    CONSTRAINT chk_report_slot_id CHECK (slot_id IN (1,2)),
    CONSTRAINT fk_report_slot_attempt FOREIGN KEY (attempt_key) REFERENCES report_generation_attempts(attempt_key) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO report_generation_slots (slot_id) VALUES (1),(2);

CREATE TABLE report_webhook_inbox (
    response_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    next_action_at DATETIME(6) NOT NULL,
    PRIMARY KEY (response_id),
    KEY idx_report_webhook_due (next_action_at, response_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
