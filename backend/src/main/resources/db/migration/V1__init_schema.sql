-- RE:ACT 초기 스키마 (ERD v1.1, 16개 테이블)
-- 상태·유형 값은 VARCHAR 로 저장하고 허용 값은 애플리케이션에서 검증한다.

CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    name          VARCHAR(100) NOT NULL,
    created_at    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE lecture_rooms (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    host_user_id BIGINT       NOT NULL,
    title        VARCHAR(200) NOT NULL,
    description  TEXT         NULL,
    join_code    VARCHAR(64)  NOT NULL,
    status       VARCHAR(20)  NOT NULL COMMENT 'READY, LIVE, ENDED',
    created_at   DATETIME     NOT NULL,
    started_at   DATETIME     NULL,
    ended_at     DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_lecture_rooms_join_code (join_code),
    KEY idx_lecture_rooms_host_user_id (host_user_id),
    CONSTRAINT fk_lecture_rooms_host_user FOREIGN KEY (host_user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE lecture_participants (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    lecture_room_id        BIGINT       NOT NULL,
    nickname               VARCHAR(50)  NOT NULL,
    participant_token_hash VARCHAR(255) NOT NULL,
    joined_at              DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_lecture_participants_token_hash (participant_token_hash),
    KEY idx_lecture_participants_room (lecture_room_id),
    CONSTRAINT fk_lecture_participants_room FOREIGN KEY (lecture_room_id) REFERENCES lecture_rooms (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 작성자는 청중(participant_id) 또는 강연자(host_user_id) 중 정확히 하나다.
-- AI 청중 의견 분석은 participant_id 가 있는 메시지만 대상으로 한다.
CREATE TABLE chat_messages (
    id              BIGINT   NOT NULL AUTO_INCREMENT,
    lecture_room_id BIGINT   NOT NULL,
    participant_id  BIGINT   NULL,
    host_user_id    BIGINT   NULL,
    content         TEXT     NOT NULL,
    received_at     DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_chat_messages_room_received (lecture_room_id, received_at, id),
    KEY idx_chat_messages_participant (participant_id),
    KEY idx_chat_messages_host_user (host_user_id),
    CONSTRAINT fk_chat_messages_room FOREIGN KEY (lecture_room_id) REFERENCES lecture_rooms (id),
    CONSTRAINT fk_chat_messages_participant FOREIGN KEY (participant_id) REFERENCES lecture_participants (id),
    CONSTRAINT fk_chat_messages_host_user FOREIGN KEY (host_user_id) REFERENCES users (id),
    CONSTRAINT ck_chat_messages_sender CHECK ((participant_id IS NULL) <> (host_user_id IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE participant_understandings (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    participant_id BIGINT      NOT NULL,
    current_status VARCHAR(30) NOT NULL COMMENT 'UNDERSTOOD, CONFUSED, NOT_UNDERSTOOD',
    updated_at     DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_participant_understandings_participant (participant_id),
    CONSTRAINT fk_participant_understandings_participant FOREIGN KEY (participant_id) REFERENCES lecture_participants (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE understanding_histories (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    participant_id  BIGINT      NOT NULL,
    previous_status VARCHAR(30) NULL,
    new_status      VARCHAR(30) NOT NULL,
    changed_at      DATETIME    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_understanding_histories_participant_changed (participant_id, changed_at),
    CONSTRAINT fk_understanding_histories_participant FOREIGN KEY (participant_id) REFERENCES lecture_participants (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE polls (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    lecture_room_id BIGINT       NOT NULL,
    question        VARCHAR(500) NOT NULL,
    status          VARCHAR(20)  NOT NULL COMMENT 'OPEN, CLOSED',
    created_at      DATETIME     NOT NULL,
    closed_at       DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_polls_room_status (lecture_room_id, status),
    CONSTRAINT fk_polls_room FOREIGN KEY (lecture_room_id) REFERENCES lecture_rooms (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE poll_options (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    poll_id       BIGINT       NOT NULL,
    content       VARCHAR(255) NOT NULL,
    display_order INT          NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_poll_options_poll_order (poll_id, display_order),
    CONSTRAINT fk_poll_options_poll FOREIGN KEY (poll_id) REFERENCES polls (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE poll_responses (
    id             BIGINT   NOT NULL AUTO_INCREMENT,
    poll_id        BIGINT   NOT NULL,
    poll_option_id BIGINT   NOT NULL,
    participant_id BIGINT   NOT NULL,
    responded_at   DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_poll_responses_poll_participant (poll_id, participant_id),
    KEY idx_poll_responses_option (poll_option_id),
    KEY idx_poll_responses_participant (participant_id),
    CONSTRAINT fk_poll_responses_poll FOREIGN KEY (poll_id) REFERENCES polls (id),
    CONSTRAINT fk_poll_responses_option FOREIGN KEY (poll_option_id) REFERENCES poll_options (id),
    CONSTRAINT fk_poll_responses_participant FOREIGN KEY (participant_id) REFERENCES lecture_participants (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE stt_sessions (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    lecture_room_id BIGINT      NOT NULL,
    status          VARCHAR(20) NOT NULL COMMENT 'RECORDING, STOPPED, ERROR',
    started_at      DATETIME    NOT NULL,
    ended_at        DATETIME    NULL,
    PRIMARY KEY (id),
    KEY idx_stt_sessions_room (lecture_room_id),
    CONSTRAINT fk_stt_sessions_room FOREIGN KEY (lecture_room_id) REFERENCES lecture_rooms (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE stt_segments (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    stt_session_id   BIGINT       NOT NULL,
    segment_key      VARCHAR(100) NOT NULL,
    transcript       TEXT         NOT NULL,
    segment_start_at DATETIME     NOT NULL,
    segment_end_at   DATETIME     NOT NULL,
    received_at      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stt_segments_session_key (stt_session_id, segment_key),
    KEY idx_stt_segments_session_start (stt_session_id, segment_start_at),
    CONSTRAINT fk_stt_segments_session FOREIGN KEY (stt_session_id) REFERENCES stt_sessions (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE stt_gaps (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    stt_session_id BIGINT       NOT NULL,
    event_key      VARCHAR(100) NOT NULL COMMENT 'STT 누락 시작 이벤트의 중복 전송 방지 식별자',
    started_at     DATETIME     NOT NULL,
    ended_at       DATETIME     NULL,
    reason         VARCHAR(50)  NOT NULL COMMENT 'ERROR, MANUAL_STOP, PERMISSION_DENIED',
    PRIMARY KEY (id),
    UNIQUE KEY uk_stt_gaps_session_event (stt_session_id, event_key),
    KEY idx_stt_gaps_session_started (stt_session_id, started_at),
    CONSTRAINT fk_stt_gaps_session FOREIGN KEY (stt_session_id) REFERENCES stt_sessions (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE ai_analyses (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    lecture_room_id BIGINT       NOT NULL,
    window_start_at DATETIME     NOT NULL,
    window_end_at   DATETIME     NOT NULL,
    status          VARCHAR(20)  NOT NULL COMMENT 'PENDING, PROCESSING, COMPLETED, FAILED',
    model_name      VARCHAR(100) NULL,
    error_message   VARCHAR(500) NULL,
    created_at      DATETIME     NOT NULL,
    completed_at    DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_analyses_room_window (lecture_room_id, window_start_at, window_end_at),
    CONSTRAINT fk_ai_analyses_room FOREIGN KEY (lecture_room_id) REFERENCES lecture_rooms (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 주제별 청중 의견 요약이다. 대표 질문이 아니며, 혼합된 의견이면 MIXED 로 분류하고 상반된 의견을 요약에 보존한다.
CREATE TABLE ai_topic_groups (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    analysis_id BIGINT       NOT NULL,
    topic       VARCHAR(200) NOT NULL,
    category    VARCHAR(30)  NOT NULL COMMENT 'QUESTION, OPINION, REQUEST, CONFUSION, MIXED',
    summary     TEXT         NOT NULL,
    created_at  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ai_topic_groups_analysis (analysis_id),
    CONSTRAINT fk_ai_topic_groups_analysis FOREIGN KEY (analysis_id) REFERENCES ai_analyses (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE ai_topic_group_messages (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    topic_group_id  BIGINT NOT NULL,
    chat_message_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_topic_group_messages_group_message (topic_group_id, chat_message_id),
    KEY idx_ai_topic_group_messages_message (chat_message_id),
    CONSTRAINT fk_ai_topic_group_messages_group FOREIGN KEY (topic_group_id) REFERENCES ai_topic_groups (id),
    CONSTRAINT fk_ai_topic_group_messages_message FOREIGN KEY (chat_message_id) REFERENCES chat_messages (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE lecture_reports (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    lecture_room_id BIGINT       NOT NULL,
    status          VARCHAR(20)  NOT NULL COMMENT 'PENDING, PROCESSING, COMPLETED, FAILED',
    content         JSON         NULL,
    error_message   VARCHAR(500) NULL,
    created_at      DATETIME     NOT NULL,
    generated_at    DATETIME     NULL,
    updated_at      DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_lecture_reports_room (lecture_room_id),
    CONSTRAINT fk_lecture_reports_room FOREIGN KEY (lecture_room_id) REFERENCES lecture_rooms (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
