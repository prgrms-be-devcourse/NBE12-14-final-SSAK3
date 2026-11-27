package com.react.room;

import com.react.room.service.LectureRoomExpiryScheduler;
import com.react.room.service.LectureRoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LectureRoomExpirySchedulerTest {

    private static final ZoneId SEOUL =
            ZoneId.of("Asia/Seoul");

    private static final Duration MAX_LECTURE_DURATION =
            Duration.ofMinutes(180);

    @Mock
    private LectureRoomService roomService;

    private LectureRoomExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new LectureRoomExpiryScheduler(
                roomService,
                MAX_LECTURE_DURATION
        );
    }

    @Test
    void 현재_시각에서_180분을_뺀_기준으로_만료_강연을_조회한다() {

        when(roomService.findExpiredRoomIds(
                any(LocalDateTime.class)
        )).thenReturn(List.of());

        LocalDateTime before = LocalDateTime.now(SEOUL);

        scheduler.expireRooms();

        LocalDateTime after = LocalDateTime.now(SEOUL);

        ArgumentCaptor<LocalDateTime> cutoffCaptor =
                ArgumentCaptor.forClass(LocalDateTime.class);

        verify(roomService)
                .findExpiredRoomIds(cutoffCaptor.capture());

        LocalDateTime cutoff = cutoffCaptor.getValue();

        assertThat(cutoff)
                .isBetween(
                        before.minus(MAX_LECTURE_DURATION),
                        after.minus(MAX_LECTURE_DURATION)
                );

        verify(roomService, never())
                .endExpiredRoom(
                        anyLong(),
                        any(LocalDateTime.class)
                );
    }

    @Test
    void 조회된_만료_강연방을_모두_종료한다() {

        when(roomService.findExpiredRoomIds(
                any(LocalDateTime.class)
        )).thenReturn(List.of(1L, 2L, 3L));

        when(roomService.endExpiredRoom(
                anyLong(),
                any(LocalDateTime.class)
        )).thenReturn(true);

        scheduler.expireRooms();

        ArgumentCaptor<LocalDateTime> cutoffCaptor =
                ArgumentCaptor.forClass(LocalDateTime.class);

        verify(roomService)
                .findExpiredRoomIds(cutoffCaptor.capture());

        LocalDateTime cutoff = cutoffCaptor.getValue();

        // 조회와 실제 종료에 동일한 기준 시각을 사용한다.
        verify(roomService).endExpiredRoom(1L, cutoff);
        verify(roomService).endExpiredRoom(2L, cutoff);
        verify(roomService).endExpiredRoom(3L, cutoff);
    }

    @Test
    void 특정_강연방의_종료에_실패해도_다른_강연방은_종료한다() {

        when(roomService.findExpiredRoomIds(
                any(LocalDateTime.class)
        )).thenReturn(List.of(1L, 2L, 3L));

        when(roomService.endExpiredRoom(
                eq(1L),
                any(LocalDateTime.class)
        )).thenThrow(new IllegalStateException("강연 종료 실패"));

        when(roomService.endExpiredRoom(
                eq(2L),
                any(LocalDateTime.class)
        )).thenReturn(true);

        when(roomService.endExpiredRoom(
                eq(3L),
                any(LocalDateTime.class)
        )).thenReturn(true);

        scheduler.expireRooms();

        verify(roomService).endExpiredRoom(
                eq(1L),
                any(LocalDateTime.class)
        );

        verify(roomService).endExpiredRoom(
                eq(2L),
                any(LocalDateTime.class)
        );

        verify(roomService).endExpiredRoom(
                eq(3L),
                any(LocalDateTime.class)
        );
    }

    @Test
    void 이미_종료된_강연방이_있어도_다음_강연방을_처리한다() {

        when(roomService.findExpiredRoomIds(
                any(LocalDateTime.class)
        )).thenReturn(List.of(1L, 2L));

        when(roomService.endExpiredRoom(
                eq(1L),
                any(LocalDateTime.class)
        )).thenReturn(false);

        when(roomService.endExpiredRoom(
                eq(2L),
                any(LocalDateTime.class)
        )).thenReturn(true);

        scheduler.expireRooms();

        verify(roomService).endExpiredRoom(
                eq(1L),
                any(LocalDateTime.class)
        );

        verify(roomService).endExpiredRoom(
                eq(2L),
                any(LocalDateTime.class)
        );
    }

    @Test
    void 최대_강연_시간을_0으로_설정할_수_없다() {

        assertThatThrownBy(() ->
                new LectureRoomExpiryScheduler(
                        roomService,
                        Duration.ZERO
                ))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 최대_강연_시간을_음수로_설정할_수_없다() {

        assertThatThrownBy(() ->
                new LectureRoomExpiryScheduler(
                        roomService,
                        Duration.ofMinutes(-1)
                ))
                .isInstanceOf(IllegalArgumentException.class);
    }
}