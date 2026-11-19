package com.react.room.repository;

import com.react.room.entity.LectureParticipant;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LectureParticipantRepository
        extends JpaRepository<LectureParticipant, Long> {

    @EntityGraph(attributePaths = "lectureRoom")
    Optional<LectureParticipant> findByParticipantTokenHash(
            String participantTokenHash
    );
}
