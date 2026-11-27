package com.react.room.service;

import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.room.entity.LectureParticipant;
import com.react.room.entity.LectureRoom;
import com.react.room.entity.LectureRoomStatus;
import com.react.room.repository.LectureParticipantRepository;
import com.react.room.repository.LectureRoomRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class ParticipantJoinWriteService {

    private final LectureRoomRepository roomRepository;
    private final LectureParticipantRepository participantRepository;
    private final int maxParticipantsPerRoom;

    public ParticipantJoinWriteService(
            LectureRoomRepository roomRepository,
            LectureParticipantRepository participantRepository,
            @Value("${app.limits.max-participants-per-room:50}")
            int maxParticipantsPerRoom
    ) {
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.maxParticipantsPerRoom = maxParticipantsPerRoom;
    }

    /**
     * 최초 입장 저장을 하나의 트랜잭션으로 처리한다.
     *
     * 강연방 행 잠금 -> 기존 토큰 재확인 -> 종료 여부 확인
     * -> 청중 정원 확인 -> INSERT
     *
     * 강연 종료도 같은 강연방 행을 잠그므로,
     * 종료 후에 신규 참여자가 저장되는 경합을 방지한다.
     *
     * 청중 수 확인과 저장도 같은 잠금 안에서 처리하여
     * 동시 입장 시 최대 청중 수를 초과하지 않도록 한다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SaveResult saveIfAbsent(
            Long roomId,
            String nickname,
            String tokenHash
    ) {
        LectureRoom room = roomRepository.findForUpdate(roomId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ROOM_NOT_FOUND)
                );

        // 다른 요청이 잠금을 보유한 사이 같은 키로 입장했을 수 있으므로
        // 잠금을 얻은 후 한 번 더 확인한다.
        Optional<LectureParticipant> previous =
                participantRepository.findByParticipantTokenHash(tokenHash);

        if (previous.isPresent()) {
            return new SaveResult(previous.get(), false);
        }

        // 반드시 방 행 잠금을 보유한 상태에서 확인해야 한다.
        if (room.getStatus() == LectureRoomStatus.ENDED) {
            throw new BusinessException(ErrorCode.ROOM_ENDED);
        }

        // 기존 청중의 재입장은 정원에 영향을 주지 않는다.
        // 신규 참여자를 저장할 때만 최대 청중 수를 확인한다.
        long participantCount =
                participantRepository.countByLectureRoom_Id(roomId);

        if (participantCount >= maxParticipantsPerRoom) {
            throw new BusinessException(ErrorCode.ROOM_PARTICIPANT_LIMIT_EXCEEDED);
        }

        LectureParticipant saved = participantRepository.saveAndFlush(
                new LectureParticipant(
                        room,
                        nickname,
                        tokenHash
                )
        );

        return new SaveResult(saved, true);
    }

    public record SaveResult(
            LectureParticipant participant,
            boolean created
    ) {
    }
}
