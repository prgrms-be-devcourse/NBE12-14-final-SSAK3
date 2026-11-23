ALTER TABLE stt_segments
    MODIFY segment_start_at DATETIME(3) NOT NULL,
    MODIFY segment_end_at DATETIME(3) NOT NULL;
