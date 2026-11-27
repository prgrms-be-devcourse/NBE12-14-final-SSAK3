-- 전체 강연방 시작 요청의 동시성 제어를 위한 공통 잠금 행
CREATE TABLE lecture_room_start_lock (
                                         id INT NOT NULL,
                                         PRIMARY KEY (id)
) ENGINE = InnoDB;

INSERT INTO lecture_room_start_lock (id)
VALUES (1);

-- 진행 중인 강연방 및 시간 초과 강연방 조회 최적화
CREATE INDEX idx_lecture_rooms_status_started_at
    ON lecture_rooms (status, started_at);