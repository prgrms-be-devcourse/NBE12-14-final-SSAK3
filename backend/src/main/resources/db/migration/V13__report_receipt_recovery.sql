-- 새 버전에서 종료되는 강연만 자동 접수 복구 대상으로 표시한다.
ALTER TABLE lecture_rooms
    ADD COLUMN report_receipt_due BOOLEAN NOT NULL DEFAULT FALSE,
    ADD INDEX idx_rooms_report_receipt_due (report_receipt_due, id);

CREATE INDEX idx_reports_status_id ON lecture_reports (status, id);
