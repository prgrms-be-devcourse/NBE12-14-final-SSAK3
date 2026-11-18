-- 이해도를 투표형 이해도 체크(polls.type=UNDERSTANDING)로 통합하는 기획 변경(docs/db/erd.dbml v1.2).
-- 상시 이해도 방식의 표(변경 이력, 최신 상태)는 투표 응답(poll_responses)으로 대체되어 삭제한다.

DROP TABLE understanding_histories;
DROP TABLE participant_understandings;

ALTER TABLE polls
    ADD COLUMN type         VARCHAR(20) NOT NULL DEFAULT 'GENERAL' COMMENT 'GENERAL, UNDERSTANDING' AFTER lecture_room_id,
    ADD COLUMN closes_at    DATETIME    NULL COMMENT '마감 예정 시각 = created_at + 제한 시간(초)' AFTER created_at,
    ADD COLUMN close_reason VARCHAR(20) NULL COMMENT 'HOST, TIMEOUT, ROOM_ENDED. OPEN 이면 NULL';

-- 기존 행은 제한 시간이 없던 투표라 생성 시각에 마감된 것으로 본다.
UPDATE polls SET closes_at = created_at WHERE closes_at IS NULL;

ALTER TABLE polls
    MODIFY COLUMN closes_at DATETIME NOT NULL COMMENT '마감 예정 시각 = created_at + 제한 시간(초)',
    ADD KEY idx_polls_status_closes_at (status, closes_at);

ALTER TABLE poll_options
    ADD COLUMN understanding_status VARCHAR(30) NULL
        COMMENT 'UNDERSTOOD, CONFUSED, NOT_UNDERSTOOD. type=UNDERSTANDING 투표의 선택지에만 값이 있다' AFTER display_order;
