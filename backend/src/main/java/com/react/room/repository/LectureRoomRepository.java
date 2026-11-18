package com.react.room.repository;

import com.react.room.entity.LectureRoom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LectureRoomRepository extends JpaRepository<LectureRoom, Long> {

    boolean existsByJoinCode(String joinCode);

    Optional<LectureRoom> findByJoinCode(String joinCode);
}