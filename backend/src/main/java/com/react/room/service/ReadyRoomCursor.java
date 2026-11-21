package com.react.room.service;

import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;

import java.time.DateTimeException;
import java.time.LocalDateTime;

// Both sort keys travel with the cursor, even when the room used as the boundary has since started.
record ReadyRoomCursor(LocalDateTime createdAt, long roomId) {
    String encode() {
        return createdAt + "~" + roomId;
    }

    static ReadyRoomCursor parse(String value) {
        String[] parts = value.split("~", -1);
        if (parts.length != 2) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        try {
            LocalDateTime createdAt = LocalDateTime.parse(parts[0]);
            long roomId = Long.parseLong(parts[1]);
            if (roomId < 1 || createdAt.getYear() < 1000 || createdAt.getYear() > 9999) {
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
            return new ReadyRoomCursor(createdAt, roomId);
        } catch (DateTimeException | NumberFormatException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }
}
