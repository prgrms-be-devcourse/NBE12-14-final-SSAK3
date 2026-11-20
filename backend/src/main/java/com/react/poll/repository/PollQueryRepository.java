package com.react.poll.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** 엔티티가 없는 강연방 테이블을 확인하고 잠그는 저장소. */
@Repository
@RequiredArgsConstructor
public class PollQueryRepository {
    private final JdbcClient jdbc;

    public record RoomInfo(String status, String hostEmail) { }

    /** 강연방 소유자만 읽는 조회. 잠그지 않는다. */
    public Optional<RoomInfo> findRoom(long roomId) {
        return jdbc.sql("""
                        SELECT r.status, u.email FROM lecture_rooms r
                        JOIN users u ON u.id = r.host_user_id WHERE r.id = ?
                        """)
                .param(roomId)
                .query((rs, rowNum) -> new RoomInfo(rs.getString("status"), rs.getString("email")))
                .optional();
    }

    /** 참여자가 해당 강연방 소속인지. 다른 강연방의 참여자 ID 로는 응답할 수 없다. */
    public boolean existsParticipant(long roomId, long participantId) {
        return jdbc.sql("SELECT COUNT(*) FROM lecture_participants WHERE id = ? AND lecture_room_id = ?")
                .params(participantId, roomId)
                .query(Long.class)
                .single() > 0;
    }

    /**
     * 쓰기 잠금 읽기. 같은 강연방의 투표 생성이 한 줄로 처리되어 열린 투표가 둘이 되지 않고,
     * 강연 종료의 UPDATE 와도 순서가 갈린다. 잠금 순서는 강연방 → 투표.
     */
    public Optional<RoomInfo> lockRoomForUpdate(long roomId) {
        return jdbc.sql("""
                        SELECT r.status, u.email FROM lecture_rooms r
                        JOIN users u ON u.id = r.host_user_id WHERE r.id = ? FOR UPDATE OF r
                        """)
                .param(roomId)
                .query((rs, rowNum) -> new RoomInfo(rs.getString("status"), rs.getString("email")))
                .optional();
    }

    /** 공유 잠금 읽기. 강연 종료(UPDATE)는 이 트랜잭션이 끝날 때까지 기다린다. */
    public Optional<RoomInfo> lockRoomForShare(long roomId) {
        return jdbc.sql("""
                        SELECT r.status, u.email FROM lecture_rooms r
                        JOIN users u ON u.id = r.host_user_id WHERE r.id = ? FOR SHARE OF r
                        """)
                .param(roomId)
                .query((rs, rowNum) -> new RoomInfo(rs.getString("status"), rs.getString("email")))
                .optional();
    }
}
