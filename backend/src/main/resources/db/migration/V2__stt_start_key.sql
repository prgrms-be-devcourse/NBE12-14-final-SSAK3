-- Sessions created before start retries were supported have no client key.
ALTER TABLE stt_sessions
    ADD COLUMN start_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uk_stt_sessions_room_start_key (lecture_room_id, start_key);
