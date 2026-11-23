package com.react.report.service;

import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.report.repository.ReportInputRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class ReportRoomAccess {
    private final ReportInputRepository rooms;

    ReportInputRepository.Room requireOwnedEndedRoom(long roomId, String hostEmail) {
        var room = rooms.findRoom(roomId).orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        if (!room.email().equalsIgnoreCase(hostEmail)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (!"ENDED".equals(room.status())) {
            throw new BusinessException(ErrorCode.ROOM_NOT_ENDED);
        }
        return room;
    }
}
