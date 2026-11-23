CREATE INDEX idx_lecture_rooms_host_status_ended_id
    ON lecture_rooms (host_user_id, status, ended_at, id);
