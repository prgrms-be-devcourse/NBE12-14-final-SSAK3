package com.react.feed.repository;

import com.react.feed.entity.ParticipantUnderstanding;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ParticipantUnderstandingRepository extends JpaRepository<ParticipantUnderstanding, Long> {
    Optional<ParticipantUnderstanding> findByParticipantId(Long participantId);

    /** 잠금 읽기. 참여자 행 잠금을 기다리는 동안 다른 요청이 커밋한 값까지 최신으로 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM ParticipantUnderstanding u WHERE u.participantId = :participantId")
    Optional<ParticipantUnderstanding> findForUpdateByParticipantId(@Param("participantId") Long participantId);
}
