package com.react.feed.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** 엔티티가 없는 강연방·참여자 테이블을 확인하는 조회 전용 저장소. */
@Repository
@RequiredArgsConstructor
public class UnderstandingQueryRepository {
    private final JdbcClient jdbc;

    public Optional<String> findRoomStatus(long roomId) {
        return jdbc.sql("SELECT status FROM lecture_rooms WHERE id = ?")
                .param(roomId).query(String.class).optional();
    }

    /**
     * 공유 잠금 읽기. 일반 조회는 트랜잭션 시작 시점의 스냅샷을 읽어서, 그 뒤에 커밋된 종료를 놓친다.
     * 이 트랜잭션이 끝날 때까지 강연방 행의 UPDATE(종료)는 기다리고, 이미 종료됐다면 ENDED 를 읽는다.
     */
    public Optional<String> findRoomStatusForShare(long roomId) {
        return jdbc.sql("SELECT status FROM lecture_rooms WHERE id = ? FOR SHARE")
                .param(roomId).query(String.class).optional();
    }

    /** 그 강연방의 참여자인지 확인하며 행을 잠근다. 같은 참여자의 동시 요청이 순서대로 처리된다. */
    public boolean lockParticipant(long roomId, long participantId) {
        return jdbc.sql("SELECT id FROM lecture_participants WHERE id = ? AND lecture_room_id = ? FOR UPDATE")
                .params(participantId, roomId).query(Long.class).optional().isPresent();
    }

    public boolean existsParticipant(long roomId, long participantId) {
        return jdbc.sql("SELECT COUNT(*) FROM lecture_participants WHERE id = ? AND lecture_room_id = ?")
                .params(participantId, roomId).query(Long.class).single() > 0;
    }
}
