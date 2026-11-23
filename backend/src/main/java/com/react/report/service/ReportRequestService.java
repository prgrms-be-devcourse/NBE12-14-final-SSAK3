package com.react.report.service;

import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.report.dto.ReportReceipt;
import com.react.report.event.ReportReadinessRequested;
import com.react.report.repository.ReportRequestRepository;
import com.react.room.entity.LectureRoom;
import com.react.room.entity.LectureRoomStatus;
import com.react.room.repository.LectureRoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReportRequestService {
    private final LectureRoomRepository rooms;
    private final ReportRequestRepository reports;
    private final ApplicationEventPublisher events;

    @Transactional
    public ReportReceipt request(long roomId, String hostEmail) {
        var room = lockRoom(roomId);
        if (!room.getHost().getEmail().equalsIgnoreCase(hostEmail)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return receive(room);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReportReceipt requestEndedRoom(long roomId) {
        return receive(lockRoom(roomId));
    }

    private LectureRoom lockRoom(long roomId) {
        return rooms.findForUpdate(roomId).orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
    }

    private ReportReceipt receive(LectureRoom room) {
        if (room.getStatus() != LectureRoomStatus.ENDED) {
            throw new BusinessException(ErrorCode.ROOM_NOT_ENDED);
        }
        long roomId = room.getId();
        var existing = reports.findByRoom(roomId);
        if (existing.isPresent()) {
            return existing.get();
        }
        reports.insertPending(roomId);
        var receipt = reports.findByRoom(roomId).orElseThrow();
        events.publishEvent(new ReportReadinessRequested(roomId));
        return receipt;
    }
}
