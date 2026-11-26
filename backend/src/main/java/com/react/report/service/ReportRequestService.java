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
        return receive(lockOwnedRoom(roomId, hostEmail), true);
    }

    @Transactional
    public ReportReceipt retry(long roomId, String hostEmail) {
        var room = lockOwnedRoom(roomId, hostEmail);
        requireEnded(room);
        if (reports.retryTemporaryFailure(roomId)) {
            events.publishEvent(new ReportReadinessRequested(roomId));
        }
        return reports.findByRoom(roomId).orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
    }

    private LectureRoom lockOwnedRoom(long roomId, String hostEmail) {
        var room = lockRoom(roomId);
        if (!room.getHost().getEmail().equalsIgnoreCase(hostEmail)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return room;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReportReceipt requestEndedRoom(long roomId) {
        return receive(lockRoom(roomId), true);
    }

    @Transactional
    public void recoverReceipt(long roomId) {
        var room = lockRoom(roomId);
        if (room.isReportReceiptDue()) {
            // 이번 복구 주기에서 생성 준비가 끝난 보고서만 따로 제출한다.
            receive(room, false);
        }
    }

    private LectureRoom lockRoom(long roomId) {
        return rooms.findForUpdate(roomId).orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
    }

    private void requireEnded(LectureRoom room) {
        if (room.getStatus() != LectureRoomStatus.ENDED) {
            throw new BusinessException(ErrorCode.ROOM_NOT_ENDED);
        }
    }

    private ReportReceipt receive(LectureRoom room, boolean notifyWorker) {
        requireEnded(room);
        // 같은 트랜잭션에서 접수가 롤백되면 자동 복구 표시도 남는다.
        room.markReportReceived();
        long roomId = room.getId();
        var existing = reports.findByRoom(roomId);
        if (existing.isPresent()) {
            return existing.get();
        }
        reports.insertPending(roomId);
        var receipt = reports.findByRoom(roomId).orElseThrow();
        if (notifyWorker) events.publishEvent(new ReportReadinessRequested(roomId));
        return receipt;
    }
}
