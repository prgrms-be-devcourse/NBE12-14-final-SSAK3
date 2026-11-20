package com.react.poll.service;

import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.poll.dto.CreatePollRequest;
import com.react.poll.dto.MyPollResponseView;
import com.react.poll.dto.PollCloseView;
import com.react.poll.dto.PollListView;
import com.react.poll.dto.PollOptionResultView;
import com.react.poll.dto.PollOptionView;
import com.react.poll.dto.PollResponseView;
import com.react.poll.dto.PollResultView;
import com.react.poll.dto.PollView;
import com.react.poll.entity.Poll;
import com.react.poll.entity.PollCloseReason;
import com.react.poll.entity.PollResponse;
import com.react.poll.entity.PollStatus;
import com.react.poll.entity.PollType;
import com.react.poll.repository.PollQueryRepository;
import com.react.poll.repository.PollRepository;
import com.react.poll.repository.PollResponseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PollService {
    static final int DEFAULT_DURATION_SECONDS = 60;
    static final int MIN_DURATION_SECONDS = 10;
    static final int MAX_DURATION_SECONDS = 300;
    static final String DEFAULT_UNDERSTANDING_QUESTION = "지금 설명을 이해하셨나요?";
    private static final int MIN_OPTIONS = 2;
    private static final int MAX_OPTIONS = 5;
    private static final int MAX_QUESTION_LENGTH = 500;
    private static final int MAX_OPTION_LENGTH = 255;

    private final PollRepository polls;
    private final PollResponseRepository responses;
    private final PollQueryRepository rooms;
    private final ApplicationEventPublisher events;

    @Transactional
    public PollView create(long roomId, String hostEmail, CreatePollRequest request) {
        // 강연방 행을 쓰기 잠금으로 읽어 같은 방의 투표 생성을 한 줄로 세우고, 강연 종료와의 순서도 가른다.
        var room = rooms.lockRoomForUpdate(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        requireOwner(room, hostEmail);
        requireLive(room.status());

        PollType type = request.type() == null ? PollType.GENERAL : request.type();
        int duration = request.durationSeconds() == null ? DEFAULT_DURATION_SECONDS : request.durationSeconds();
        if (duration < MIN_DURATION_SECONDS || duration > MAX_DURATION_SECONDS) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        Instant now = now();
        // 스케줄러가 아직 마감하지 못한 만료 투표는 여기서 먼저 마감한다. 시간이 남은 투표가 있을 때만 거절한다.
        for (Poll open : polls.findByLectureRoomIdAndStatus(roomId, PollStatus.OPEN)) {
            if (!open.isExpired(now)) {
                throw new BusinessException(ErrorCode.POLL_ALREADY_OPEN);
            }
            open.expire(rooms.countParticipantsAtTimeout(roomId, open.getId(), open.getClosesAt()));
        }

        Poll poll = type == PollType.UNDERSTANDING
                ? Poll.understanding(roomId, understandingQuestion(request.question()), now, duration)
                : Poll.general(roomId, generalQuestion(request.question()), generalOptions(request.options()), now, duration);
        return toView(polls.save(poll), now);
    }

    @Transactional
    public PollCloseView close(long roomId, long pollId, String hostEmail) {
        // 잠금 순서는 강연방 → 투표. 강연방을 공유 잠금으로 읽어 강연 종료와 순서가 엇갈리지 않게 한다.
        var room = rooms.lockRoomForShare(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        requireOwner(room, hostEmail);

        Poll poll = polls.findForUpdate(roomId, pollId)
                .orElseThrow(() -> new BusinessException(ErrorCode.POLL_NOT_FOUND));
        Instant now = now();
        // 제한 시간이 지난 투표는 이미 마감된 것으로 본다. status 는 스케줄러나 다음 쓰기 요청이 갱신한다.
        if (poll.effectiveStatus(now) == PollStatus.CLOSED) {
            throw new BusinessException(ErrorCode.POLL_ALREADY_CLOSED);
        }
        poll.closeByHost(now, rooms.countParticipants(roomId));
        return new PollCloseView(poll.getId(), poll.getStatus(), poll.getClosedAt(), poll.getCloseReason());
    }

    /**
     * 청중이 투표에 응답하거나 마감 전에 선택을 바꾼다. participantId 는 서버가 확인한 값만 받는다.
     * 잠금 순서는 강연방(공유) → 투표(쓰기)라 강연 종료·투표 마감과 같은 순서로 직렬화된다.
     */
    @Transactional
    public PollResponseView respond(long roomId, long pollId, long participantId, long optionId) {
        var room = rooms.lockRoomForShare(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        if (!rooms.existsParticipant(roomId, participantId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        requireLive(room.status());

        Poll poll = polls.findForUpdate(roomId, pollId)
                .orElseThrow(() -> new BusinessException(ErrorCode.POLL_NOT_FOUND));
        Instant now = now();
        // 마감 판단과 저장이 같은 트랜잭션이라 마감 시각이 지난 요청은 저장되지 않는다.
        if (poll.effectiveStatus(now) == PollStatus.CLOSED) {
            throw new BusinessException(ErrorCode.POLL_CLOSED);
        }
        if (poll.getOptions().stream().noneMatch(option -> option.getId().equals(optionId))) {
            throw new BusinessException(ErrorCode.INVALID_POLL_OPTION);
        }

        var existing = responses.findByPollIdAndParticipantId(pollId, participantId);
        if (existing.isEmpty()) {
            var saved = responses.save(new PollResponse(pollId, optionId, participantId, now));
            events.publishEvent(new PollResponseSavedEvent(roomId, pollId));
            return responseView(saved, true, poll);
        }
        PollResponse response = existing.get();
        boolean changed = !response.getPollOptionId().equals(optionId);
        if (changed) {
            response.changeOption(optionId, now);
            // 방송은 이 트랜잭션이 커밋된 뒤에 PollResultsBroadcaster 가 한다. 선택이 바뀌지 않았으면 알리지 않는다.
            events.publishEvent(new PollResponseSavedEvent(roomId, pollId));
        }
        return responseView(response, changed, poll);
    }

    /**
     * 강연 종료로 진행 중이던 투표를 마감한다. 강연 종료 API 가 방 상태를 ENDED 로 바꾸는 트랜잭션 안에서 호출한다
     * (방 행 UPDATE 가 쓰기 잠금이라 투표 생성·마감과 순서가 갈린다). 이미 제한 시간이 지난 투표는 TIMEOUT 으로 남긴다.
     *
     * @return 마감한 투표 수
     */
    @Transactional
    public int closeOpenPollsOnRoomEnded(long roomId) {
        Instant now = now();
        List<Poll> open = polls.findByLectureRoomIdAndStatus(roomId, PollStatus.OPEN);
        for (Poll poll : open) {
            if (poll.isExpired(now)) {
                poll.expire(rooms.countParticipantsAtTimeout(roomId, poll.getId(), poll.getClosesAt()));
            } else {
                poll.closeByRoomEnded(now, rooms.countParticipants(roomId));
            }
        }
        return open.size();
    }

    public PollListView list(long roomId, String hostEmail, PollStatus status) {
        var room = rooms.findRoom(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        requireOwner(room, hostEmail);
        return listOf(roomId, status);
    }

    /** 청중 조회. participantId 는 서버가 방별 쿠키로 확인한 값이며, 응답에는 질문·선택지·남은 시간만 있고 집계는 없다. */
    public PollListView listForParticipant(long roomId, PollStatus status) {
        rooms.findRoom(roomId).orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        return listOf(roomId, status);
    }

    /** 재접속한 청중이 자신의 선택을 복원한다. 응답이 없으면 선택을 비워서 돌려준다. */
    public MyPollResponseView myResponse(long roomId, long pollId, long participantId) {
        Poll poll = polls.findById(pollId)
                .filter(found -> found.getLectureRoomId().equals(roomId))
                .orElseThrow(() -> new BusinessException(ErrorCode.POLL_NOT_FOUND));
        return responses.findFirstByPollIdAndParticipantId(poll.getId(), participantId)
                .map(response -> new MyPollResponseView(poll.getId(), participantId,
                        response.getPollOptionId(), response.getRespondedAt()))
                .orElseGet(() -> new MyPollResponseView(poll.getId(), participantId, null, null));
    }

    /**
     * 투표 결과 집계. 강연방 소유자만 조회하며 진행 중에도 볼 수 있다. 읽기 전용 트랜잭션 하나에서 모두 읽어
     * 같은 시점의 데이터로 계산하므로, 읽는 도중 응답이 들어와도 인원 합계가 어긋나지 않는다. 잠그지 않아 응답 저장을 막지 않는다.
     */
    public PollResultView results(long roomId, long pollId, String hostEmail) {
        var room = rooms.findRoom(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        requireOwner(room, hostEmail);
        return aggregate(roomPoll(roomId, pollId), roomId);
    }

    /**
     * 응답 저장이 커밋된 뒤 방송할 때 쓰는 집계. 소유자 확인은 응답 저장 쪽에서 이미 끝났다.
     * 새 트랜잭션에서 읽으므로, 방금 커밋된 응답이 스냅샷에서 빠지는 일이 없다.
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public PollResultView currentResults(long roomId, long pollId) {
        return aggregate(roomPoll(roomId, pollId), roomId);
    }

    // 다른 강연방의 투표 번호로는 결과를 볼 수 없다.
    private Poll roomPoll(long roomId, long pollId) {
        return polls.findById(pollId)
                .filter(found -> found.getLectureRoomId().equals(roomId))
                .orElseThrow(() -> new BusinessException(ErrorCode.POLL_NOT_FOUND));
    }

    private PollResultView aggregate(Poll poll, long roomId) {
        long pollId = poll.getId();
        Map<Long, Long> counts = responses.countByOption(pollId).stream()
                .collect(Collectors.toMap(PollResponseRepository.OptionCount::getOptionId,
                        PollResponseRepository.OptionCount::getCount));
        // 응답자 수는 따로 세지 않고 선택지별 인원을 더해 구한다. 그래서 인원 합계가 항상 일치한다.
        long responded = counts.values().stream().mapToLong(Long::longValue).sum();
        Instant now = now();
        long total = resultParticipantCount(poll, now);

        // 응답이 없는 선택지는 쿼리에 나오지 않으므로 선택지 목록을 기준으로 0 을 채운다.
        List<PollOptionResultView> options = poll.getOptions().stream()
                .map(option -> {
                    long count = counts.getOrDefault(option.getId(), 0L);
                    return new PollOptionResultView(option.getId(), option.getContent(), option.getUnderstandingStatus(),
                            count, PollResultMath.percentage(count, responded), PollResultMath.percentage(count, total));
                })
                .toList();
        return new PollResultView(poll.getId(), poll.getType(), poll.effectiveStatus(now), poll.getClosesAt(),
                total, responded, PollResultMath.unresponded(total, responded),
                PollResultMath.percentage(responded, total), options, now);
    }

    private long resultParticipantCount(Poll poll, Instant now) {
        if (poll.getClosedTotalParticipants() != null) {
            return poll.getClosedTotalParticipants();
        }
        if (poll.isExpired(now)) {
            return rooms.countParticipantsAtTimeout(poll.getLectureRoomId(), poll.getId(), poll.getClosesAt());
        }
        return rooms.countParticipants(poll.getLectureRoomId());
    }

    private PollListView listOf(long roomId, PollStatus status) {
        Instant now = now();
        List<PollView> views = polls.findByLectureRoomIdOrderByIdDesc(roomId).stream()
                .map(poll -> toView(poll, now))
                .filter(view -> status == null || view.status() == status)
                .toList();
        return new PollListView(views, now);
    }

    private static PollResponseView responseView(PollResponse response, boolean changed, Poll poll) {
        return new PollResponseView(poll.getId(), response.getParticipantId(), response.getPollOptionId(),
                changed, response.getRespondedAt(), poll.getClosesAt());
    }

    private static PollView toView(Poll poll, Instant now) {
        List<PollOptionView> options = poll.getOptions().stream()
                .map(option -> new PollOptionView(option.getId(), option.getContent(),
                        option.getDisplayOrder(), option.getUnderstandingStatus()))
                .toList();
        PollStatus status = poll.effectiveStatus(now);
        // 마감 시각이 지났지만 아직 갱신되지 않은 투표는 TIMEOUT 으로 마감된 것처럼 보여 준다.
        boolean expiredOnly = status != poll.getStatus();
        return new PollView(poll.getId(), poll.getLectureRoomId(), poll.getType(), poll.getQuestion(), status, options,
                (int) Duration.between(poll.getCreatedAt(), poll.getClosesAt()).getSeconds(),
                poll.getCreatedAt(), poll.getClosesAt(),
                expiredOnly ? poll.getClosesAt() : poll.getClosedAt(),
                expiredOnly ? PollCloseReason.TIMEOUT : poll.getCloseReason(), now);
    }

    private static String understandingQuestion(String question) {
        if (question == null || question.isBlank()) {
            return DEFAULT_UNDERSTANDING_QUESTION;
        }
        return text(question, MAX_QUESTION_LENGTH);
    }

    private static String generalQuestion(String question) {
        if (question == null || question.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return text(question, MAX_QUESTION_LENGTH);
    }

    private static List<String> generalOptions(List<String> options) {
        if (options == null || options.size() < MIN_OPTIONS || options.size() > MAX_OPTIONS) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        List<String> contents = options.stream().map(option -> {
            if (option == null || option.isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
            return text(option, MAX_OPTION_LENGTH);
        }).toList();
        if (contents.stream().distinct().count() != contents.size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return contents;
    }

    private static String text(String value, int maxLength) {
        String stripped = value.strip();
        if (stripped.length() > maxLength) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return stripped;
    }

    private static void requireOwner(PollQueryRepository.RoomInfo room, String hostEmail) {
        if (hostEmail == null || !room.hostEmail().equalsIgnoreCase(hostEmail)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    // 투표는 LIVE 강연방에서만 만든다.
    private static void requireLive(String status) {
        if ("ENDED".equals(status)) {
            throw new BusinessException(ErrorCode.ROOM_ENDED);
        }
        if (!"LIVE".equals(status)) {
            throw new BusinessException(ErrorCode.ROOM_NOT_LIVE);
        }
    }

    // DATETIME 은 초 단위라 DB 가 반올림하지 않도록 미리 초로 자른다.
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }
}
