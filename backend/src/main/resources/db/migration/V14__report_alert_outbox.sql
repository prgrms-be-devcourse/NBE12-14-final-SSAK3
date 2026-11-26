-- 보고서 실패와 같은 트랜잭션에서 새 알림만 접수한다. 알림 대기 시각은 모두 UTC다.
CREATE TABLE report_alert_outbox (
    report_id       BIGINT       NOT NULL,
    retry_used      BOOLEAN      NOT NULL,
    diagnostic_code VARCHAR(64)  NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    next_attempt_at DATETIME(6)  NOT NULL,
    lease_token     CHAR(36)     NULL,
    PRIMARY KEY (report_id, retry_used),
    KEY idx_report_alert_due (next_attempt_at, report_id, retry_used),
    KEY idx_report_alert_lease (lease_token),
    CONSTRAINT fk_report_alert_report FOREIGN KEY (report_id) REFERENCES lecture_reports (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
