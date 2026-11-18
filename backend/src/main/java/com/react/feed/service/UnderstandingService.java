package com.react.feed.service;

import com.react.feed.dto.MyUnderstandingView;
import com.react.feed.dto.UnderstandingChangeView;
import com.react.feed.dto.UnderstandingStatus;
import com.react.feed.dto.UnderstandingSummaryView;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.Map;

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
        // 강연방 행을 공유 잠금으로 읽어 변경과 종료가 동시에 일어나도 종료 후에는 저장되지 않게 한다. 잠금 순서는 강연방 → 참여자.
        String roomStatus = repository.findRoomStatusForShare(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
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

    /** 강연방 소유자만 조회할 수 있다. 응답 인원은 최신 이해 상태 기준이라 과거 이력은 중복 포함되지 않는다. */
    public UnderstandingSummaryView getSummary(long roomId, String hostEmail) {
        String owner = repository.findHostEmail(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        if (hostEmail == null || !owner.equalsIgnoreCase(hostEmail)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        var counts = repository.countByRoom(roomId);
        long responded = counts.understood() + counts.confused() + counts.notUnderstood();
        Map<UnderstandingStatus, Long> byStatus = new EnumMap<>(UnderstandingStatus.class);
        byStatus.put(UnderstandingStatus.UNDERSTOOD, counts.understood());
        byStatus.put(UnderstandingStatus.CONFUSED, counts.confused());
        byStatus.put(UnderstandingStatus.NOT_UNDERSTOOD, counts.notUnderstood());
        Map<UnderstandingStatus, Double> percentages = new EnumMap<>(UnderstandingStatus.class);
        byStatus.forEach((status, count) -> percentages.put(status, percent(count, counts.total())));

        return new UnderstandingSummaryView(roomId, counts.total(), responded, counts.total() - responded,
                percent(responded, counts.total()), byStatus, percentages, Instant.now());
    }

    // 소수 첫째 자리까지 반올림한다. 참여자가 없으면 0 이다.
    private static double percent(long part, long whole) {
        if (whole == 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(part * 100.0 / whole).setScale(1, RoundingMode.HALF_UP).doubleValue();
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
