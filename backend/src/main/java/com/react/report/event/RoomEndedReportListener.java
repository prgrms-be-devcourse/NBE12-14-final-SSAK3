package com.react.report.event;

import com.react.report.service.ReportRequestService;
import com.react.room.entity.LectureRoomStatus;
import com.react.room.event.RoomStatusChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Component
public class RoomEndedReportListener {
    private final ReportRequestService reports;
    private final Executor executor;

    public RoomEndedReportListener(ReportRequestService reports, @Qualifier("reportReceiptExecutor") Executor executor) {
        this.reports = reports;
        this.executor = executor;
    }

    @TransactionalEventListener
    public void onRoomStatusChanged(RoomStatusChangedEvent event) {
        if (event.status() != LectureRoomStatus.ENDED) {
            return;
        }
        try {
            executor.execute(() -> receive(event.roomId()));
        } catch (RejectedExecutionException e) {
            log.warn("종료된 강연방의 보고서 자동 접수 작업을 등록하지 못했습니다. roomId={}", event.roomId(), e);
        }
    }

    private void receive(long roomId) {
        try {
            reports.requestEndedRoom(roomId);
        } catch (RuntimeException e) {
            log.warn("종료된 강연방의 보고서 자동 접수 실패. roomId={}", roomId, e);
        }
    }
}
