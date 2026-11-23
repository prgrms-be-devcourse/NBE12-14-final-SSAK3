package com.react.poll.repository;

import com.react.poll.entity.Poll;
import com.react.poll.entity.PollStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PollRepository extends JpaRepository<Poll, Long> {

    List<Poll> findByLectureRoomIdAndStatus(Long lectureRoomId, PollStatus status);

    /** 제한 시간이 지났지만 아직 OPEN 으로 남은 투표. 한 번에 너무 많이 처리하지 않도록 오래된 것부터 끊어서 읽는다. */
    List<Poll> findTop100ByStatusAndClosesAtLessThanEqualOrderByClosesAtAsc(PollStatus status, Instant now);

    @EntityGraph(attributePaths = "options")
    List<Poll> findByLectureRoomIdOrderByIdDesc(Long lectureRoomId);

    /** 마감 처리와 응답이 같은 투표를 동시에 바꾸지 못하도록 행을 잠그고 최신 값을 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Poll p where p.id = :pollId and p.lectureRoomId = :roomId")
    Optional<Poll> findForUpdate(@Param("roomId") Long roomId, @Param("pollId") Long pollId);
}
