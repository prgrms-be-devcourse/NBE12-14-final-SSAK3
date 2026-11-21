package com.react.room.repository;

import com.react.room.entity.LectureParticipant;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LectureParticipantRepository
        extends JpaRepository<LectureParticipant, Long> {

    @EntityGraph(attributePaths = "lectureRoom")
    Optional<LectureParticipant> findByParticipantTokenHash(
            String participantTokenHash
    );

    @Modifying
    @Query("delete from LectureParticipant p where p.lectureRoom.id = :roomId")
    void deleteAllInRoom(@Param("roomId") Long roomId);
}
