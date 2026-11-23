package com.react.room.service;

import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;

import java.time.DateTimeException;
import java.time.LocalDateTime;

// Both sort keys travel with the cursor. A history entry may lack a recorded end time.
record RoomPageCursor(LocalDateTime time, long roomId) {
    String encode() {
        return (time == null ? "" : time.toString()) + "~" + roomId;
    }

    static RoomPageCursor parse(String value) {
        String[] parts = value.split("~", -1);
        if (parts.length != 2) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        try {
            LocalDateTime time = parts[0].isEmpty() ? null : LocalDateTime.parse(parts[0]);
            long roomId = Long.parseLong(parts[1]);
            if (roomId < 1 || (time != null && (time.getYear() < 1000 || time.getYear() > 9999))) {
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
            return new RoomPageCursor(time, roomId);
        } catch (DateTimeException | NumberFormatException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }
}
