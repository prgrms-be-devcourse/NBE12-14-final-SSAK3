package com.react.poll.repository;

import com.react.poll.entity.PollResponse;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface PollResponseRepository extends JpaRepository<PollResponse, Long> {

    /**
     * 투표 행 잠금을 기다리는 동안 먼저 커밋된 응답을 읽어야 한다. 강연방 조회가 조인한 users 는 잠기지 않는 일반 읽기라
     * 트랜잭션 스냅샷이 이미 만들어져 있으므로, 일반 SELECT 로는 그 응답이 보이지 않아 insert 가 UNIQUE 를 어긴다.
     * 잠금 읽기는 스냅샷과 무관하게 최신 커밋 값을 읽는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PollResponse> findByPollIdAndParticipantId(Long pollId, Long participantId);
}
