package com.react.feed.service;

import com.react.feed.dto.MyUnderstandingView;
import com.react.feed.dto.UnderstandingChangeView;
import com.react.feed.dto.UnderstandingStatus;
import com.react.feed.entity.ParticipantUnderstanding;
import com.react.feed.entity.UnderstandingHistory;
import com.react.feed.repository.ParticipantUnderstandingRepository;
import com.react.feed.repository.UnderstandingHistoryRepository;
import com.react.feed.repository.UnderstandingQueryRepository;
import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class UnderstandingService {
    private final UnderstandingQueryRepository repository;
    private final ParticipantUnderstandingRepository understandings;
    private final UnderstandingHistoryRepository histories;

    @Transactional
    public UnderstandingChangeView change(long roomId, long participantId, UnderstandingStatus status) {
        if (status == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        String roomStatus = findRoomStatus(roomId);
        // 참여자 행을 잠가서 같은 청중의 동시 변경이 순서대로 처리되게 한다.
        if (!repository.lockParticipant(roomId, participantId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        requireLive(roomStatus);

        var current = understandings.findForUpdateByParticipantId(participantId);
        if (current.isPresent() && current.get().getCurrentStatus() == status) {
            return new UnderstandingChangeView(participantId, roomId, status, status, false, current.get().getUpdatedAt());
        }

        // DATETIME 은 초 단위라 DB 가 반올림하지 않도록 미리 초로 자른다.
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        UnderstandingStatus previous = current.map(ParticipantUnderstanding::getCurrentStatus).orElse(null);
        if (current.isPresent()) {
            current.get().changeTo(status, now);
        } else {
            understandings.save(ParticipantUnderstanding.of(participantId, status, now));
        }
        histories.save(UnderstandingHistory.of(participantId, previous, status, now));
        return new UnderstandingChangeView(participantId, roomId, previous, status, true, now);
    }

    public MyUnderstandingView getMine(long roomId, long participantId) {
        findRoomStatus(roomId);
        if (!repository.existsParticipant(roomId, participantId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return understandings.findByParticipantId(participantId)
                .map(u -> new MyUnderstandingView(participantId, u.getCurrentStatus(), u.getUpdatedAt()))
                .orElse(new MyUnderstandingView(participantId, null, null));
    }

    private String findRoomStatus(long roomId) {
        return repository.findRoomStatus(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
    }

    // 이해도 변경은 LIVE 강연방에서만 받는다.
    private void requireLive(String status) {
        if ("ENDED".equals(status)) {
            throw new BusinessException(ErrorCode.ROOM_ENDED);
        }
        if (!"LIVE".equals(status)) {
            throw new BusinessException(ErrorCode.ROOM_NOT_LIVE);
        }
    }
}
