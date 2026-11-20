package com.react.room.repository;

import com.react.room.entity.LectureRoom;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LectureRoomRepository extends JpaRepository<LectureRoom, Long> {

    boolean existsByJoinCode(String joinCode);

    Optional<LectureRoom> findByJoinCode(String joinCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from LectureRoom r where r.id = :roomId")
    Optional<LectureRoom> findForUpdate(@Param("roomId") Long roomId);
}
