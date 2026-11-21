package com.react.room.repository;

import com.react.auth.entity.User;
import com.react.room.entity.LectureRoom;
import com.react.room.entity.LectureRoomStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface LectureRoomRepository extends JpaRepository<LectureRoom, Long> {

    boolean existsByJoinCode(String joinCode);

    Optional<LectureRoom> findByJoinCode(String joinCode);

    interface RoomSummary {
        Long getId();
        String getTitle();
        LocalDateTime getStartedAt();
    }

    List<RoomSummary> findByHostAndStatusOrderByStartedAtDescIdDesc(User host, LectureRoomStatus status);

    interface ReadySummary {
        Long getId();
        String getTitle();
        LocalDateTime getCreatedAt();
    }

    @Query("""
            select r.id as id, r.title as title, r.createdAt as createdAt
            from LectureRoom r
            where r.host = :host and r.status = com.react.room.entity.LectureRoomStatus.READY
                and (:beforeTime is null or r.createdAt < :beforeTime
                     or (r.createdAt = :beforeTime and r.id < :beforeId))
            order by r.createdAt desc, r.id desc
            """)
    List<ReadySummary> findReadyPage(@Param("host") User host, @Param("beforeTime") LocalDateTime beforeTime,
                                     @Param("beforeId") Long beforeId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from LectureRoom r where r.id = :roomId")
    Optional<LectureRoom> findForUpdate(@Param("roomId") Long roomId);
}
