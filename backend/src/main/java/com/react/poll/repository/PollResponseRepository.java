package com.react.poll.repository;

import com.react.poll.entity.PollResponse;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PollResponseRepository extends JpaRepository<PollResponse, Long> {

    /**
     * 투표 행 잠금을 기다리는 동안 먼저 커밋된 응답을 읽어야 한다. 강연방 조회가 조인한 users 는 잠기지 않는 일반 읽기라
     * 트랜잭션 스냅샷이 이미 만들어져 있으므로, 일반 SELECT 로는 그 응답이 보이지 않아 insert 가 UNIQUE 를 어긴다.
     * 잠금 읽기는 스냅샷과 무관하게 최신 커밋 값을 읽는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PollResponse> findByPollIdAndParticipantId(Long pollId, Long participantId);

    /** 잠금 없는 읽기. 재접속 복원처럼 쓰기 없이 마지막 선택만 확인할 때 쓴다. */
    Optional<PollResponse> findFirstByPollIdAndParticipantId(Long pollId, Long participantId);

    /**
     * 선택지별 응답 인원. 응답은 청중당 1건(최종 선택)이라 중복 없이 센다. 응답이 없는 선택지는 결과에 나오지 않는다.
     * 잠그지 않는 읽기라 응답 저장을 막지 않는다.
     */
    @Query("select r.pollOptionId as optionId, count(r) as count from PollResponse r "
            + "where r.pollId = :pollId group by r.pollOptionId")
    List<OptionCount> countByOption(@Param("pollId") Long pollId);

    interface OptionCount {
        Long getOptionId();

        long getCount();
    }
}
