package com.react.room.service;

import com.react.auth.entity.User;
import com.react.auth.repository.UserRepository;
import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.poll.service.PollService;
import com.react.room.dto.CreateRoomRequest;
import com.react.room.dto.CreateRoomResponse;
import com.react.room.dto.EndRoomResponse;
import com.react.room.dto.HostRoomHomeResponse;
import com.react.room.dto.LiveRoomResponse;
import com.react.room.dto.PublicRoomResponse;
import com.react.room.dto.ReadyRoomPage;
import com.react.room.dto.RoomDetailResponse;
import com.react.room.dto.RoomHistoryPage;
import com.react.room.dto.RoomHistoryStatuses;
import com.react.room.dto.RoomInvitationResponse;
import com.react.room.dto.StartRoomResponse;
import com.react.room.entity.LectureRoom;
import com.react.room.entity.LectureRoomStatus;
import com.react.room.event.RoomDeletedEvent;
import com.react.room.event.RoomStatusChangedEvent;
import com.react.room.repository.LectureParticipantRepository;
import com.react.room.repository.LectureRoomRepository;
import com.react.room.repository.RoomHistoryRepository;
import com.react.stt.service.SttSessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class LectureRoomService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String JOIN_CODE_CHARACTERS =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int JOIN_CODE_LENGTH = 12;
    private static final int MAX_CODE_ATTEMPTS = 10;
    private static final int READY_PAGE_SIZE = 3;
    private static final int HISTORY_PAGE_SIZE = 10;
    private static final int HISTORY_STATUS_BATCH_SIZE = 50;

    private final LectureRoomRepository lectureRoomRepository;
    private final RoomHistoryRepository historyRepository;
    private final LectureParticipantRepository participantRepository;
    private final UserRepository userRepository;
    private final PollService pollService;
    private final SttSessionService sttSessionService;
    private final ApplicationEventPublisher eventPublisher;
    private final String joinBaseUrl;

    public LectureRoomService(
            LectureRoomRepository lectureRoomRepository,
            RoomHistoryRepository historyRepository,
            LectureParticipantRepository participantRepository,
            UserRepository userRepository,
            PollService pollService,
            SttSessionService sttSessionService,
            ApplicationEventPublisher eventPublisher,
            @Value("${ROOM_JOIN_BASE_URL:http://localhost:5173}")
            String joinBaseUrl
    ) {
        this.lectureRoomRepository = lectureRoomRepository;
        this.historyRepository = historyRepository;
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
        this.pollService = pollService;
        this.sttSessionService = sttSessionService;
        this.eventPublisher = eventPublisher;
        this.joinBaseUrl = joinBaseUrl;
    }

    // 강연방 생성
    @Transactional
    public CreateRoomResponse create(
            CreateRoomRequest request,
            Authentication authentication
    ) {
        User host = requireHost(authentication);
        String joinCode = generateUniqueJoinCode();

        LectureRoom room = new LectureRoom(
                host,
                request.title(),
                request.description(),
                joinCode
        );

        LectureRoom saved = lectureRoomRepository.saveAndFlush(room);

        return new CreateRoomResponse(
                saved.getId(),
                saved.getTitle(),
                saved.getDescription(),
                saved.getStatus().name(),
                saved.getJoinCode(),
                toInstant(saved.getCreatedAt())
        );
    }

    public List<LiveRoomResponse> liveRooms(Authentication authentication) {
        return liveRooms(requireHost(authentication));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public HostRoomHomeResponse home(Authentication authentication, int readyLimit) {
        User host = requireHost(authentication);
        if (readyLimit < 1) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return new HostRoomHomeResponse(liveRooms(host), readyPage(host, null, readyLimit));
    }

    public ReadyRoomPage readyRooms(Authentication authentication, String cursor) {
        User host = requireHost(authentication);
        RoomPageCursor boundary = cursor == null ? null : RoomPageCursor.parse(cursor);
        if (boundary != null && boundary.time() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return readyPage(host, boundary, READY_PAGE_SIZE);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RoomHistoryPage history(Authentication authentication, String cursor) {
        Instant serverTime = Instant.now();
        long hostId = requireHost(authentication).getId();
        RoomPageCursor boundary = cursor == null ? null : RoomPageCursor.parse(cursor);
        var entries = historyRepository.page(hostId, boundary == null ? null : boundary.time(),
                boundary == null ? null : boundary.roomId(), HISTORY_PAGE_SIZE + 1);
        String nextCursor = null;
        if (entries.size() > HISTORY_PAGE_SIZE) {
            entries = entries.subList(0, HISTORY_PAGE_SIZE);
            var last = entries.getLast();
            nextCursor = new RoomPageCursor(last.endedAt(), last.roomId()).encode();
        }
        var rooms = entries.stream().map(entry -> new RoomHistoryPage.Room(entry.roomId(), entry.title(),
                toInstant(entry.endedAt()), entry.reportStatus())).toList();
        return new RoomHistoryPage(rooms, nextCursor, historyRepository.countEnded(hostId), serverTime);
    }

    public RoomHistoryPage.Count historyCount(Authentication authentication) {
        return new RoomHistoryPage.Count(historyRepository.countEnded(requireHost(authentication).getId()));
    }

    public RoomHistoryStatuses historyStatuses(Authentication authentication, List<Long> roomIds) {
        Instant serverTime = Instant.now();
        long hostId = requireHost(authentication).getId();
        if (roomIds.isEmpty() || roomIds.size() > HISTORY_STATUS_BATCH_SIZE
                || roomIds.stream().anyMatch(id -> id == null || id < 1)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return new RoomHistoryStatuses(historyRepository.statuses(hostId, roomIds), serverTime);
    }

    private List<LiveRoomResponse> liveRooms(User host) {
        return lectureRoomRepository.findByHostAndStatusOrderByStartedAtDescIdDesc(host, LectureRoomStatus.LIVE)
                .stream()
                .map(room -> new LiveRoomResponse(room.getId(), room.getTitle(), toInstant(room.getStartedAt())))
                .toList();
    }

    private ReadyRoomPage readyPage(User host, RoomPageCursor cursor, int limit) {
        int fetchSize = limit == Integer.MAX_VALUE ? limit : limit + 1;
        var rows = lectureRoomRepository.findReadyPage(host, cursor == null ? null : cursor.time(),
                cursor == null ? null : cursor.roomId(), PageRequest.of(0, fetchSize));
        String nextCursor = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            var last = rows.getLast();
            nextCursor = new RoomPageCursor(last.getCreatedAt(), last.getId()).encode();
        }
        var rooms = rows.stream()
                .map(row -> new ReadyRoomPage.Room(row.getId(), row.getTitle(), toInstant(row.getCreatedAt())))
                .toList();
        return new ReadyRoomPage(rooms, nextCursor);
    }

    // 강연방 초대 정보 조회
    public RoomInvitationResponse invitation(
            Long roomId,
            Authentication authentication
    ) {
        LectureRoom room = findOwnedRoom(roomId, authentication);

        String baseUrl = joinBaseUrl.strip()
                .replaceAll("/+$", "");

        String joinUrl = baseUrl + "/join/" + room.getJoinCode();

        return new RoomInvitationResponse(
                room.getId(),
                room.getJoinCode(),
                joinUrl,
                joinUrl
        );
    }

    // 강연방 상세 조회
    public RoomDetailResponse detail(
            Long roomId,
            Authentication authentication
    ) {
        LectureRoom room = findOwnedRoom(roomId, authentication);

        return new RoomDetailResponse(
                room.getId(),
                room.getTitle(),
                room.getDescription(),
                room.getStatus().name(),
                room.getJoinCode(),
                toInstant(room.getCreatedAt()),
                toInstant(room.getStartedAt()),
                toInstant(room.getEndedAt())
        );
    }

    // 공개 강연방 정보 조회
    public PublicRoomResponse publicDetail(String joinCode) {

        LectureRoom room = lectureRoomRepository.findByJoinCode(joinCode)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.INVALID_JOIN_CODE)
                );

        boolean canJoin =
                room.getStatus() != LectureRoomStatus.ENDED;

        return new PublicRoomResponse(
                room.getId(),
                room.getTitle(),
                room.getDescription(),
                room.getStatus().name(),
                canJoin
        );
    }

    @Transactional
    public void deleteReadyRoom(Long roomId, Authentication authentication) {
        // 최초 입장과 강연 시작도 같은 방 행을 먼저 잠근다.
        LectureRoom room = findOwnedRoomForUpdate(roomId, authentication);
        if (room.getStatus() != LectureRoomStatus.READY) {
            throw new BusinessException(ErrorCode.INVALID_ROOM_STATUS);
        }

        participantRepository.deleteAllInRoom(roomId);
        lectureRoomRepository.delete(room);
        eventPublisher.publishEvent(new RoomDeletedEvent(roomId));
    }

    // SCRUM-130: 강연 시작
    @Transactional
    public StartRoomResponse start(
            Long roomId,
            Authentication authentication
    ) {
        LectureRoom room = findOwnedRoomForUpdate(
                roomId,
                authentication
        );

        // READY -> LIVE
        room.start();

        lectureRoomRepository.flush();

        Instant startedAt = toInstant(room.getStartedAt());

        eventPublisher.publishEvent(
                new RoomStatusChangedEvent(
                        room.getId(),
                        room.getStatus(),
                        startedAt
                )
        );

        return new StartRoomResponse(
                room.getId(),
                room.getStatus().name(),
                startedAt
        );
    }

    // SCRUM-130: 강연 종료
    @Transactional
    public EndRoomResponse end(
            Long roomId,
            Authentication authentication
    ) {
        LectureRoom room = findOwnedRoomForUpdate(
                roomId,
                authentication
        );

        // LIVE -> ENDED
        // 다른 상태에서는 INVALID_ROOM_STATUS 예외 발생
        room.end();

        lectureRoomRepository.flush();

        pollService.closeOpenPollsOnRoomEnded(room.getId());
        sttSessionService.closeOnRoomEnded(room.getId(), room.getEndedAt());

        Instant endedAt = toInstant(room.getEndedAt());

        // 트랜잭션 커밋 이후 WebSocket 이벤트 발행
        eventPublisher.publishEvent(
                new RoomStatusChangedEvent(
                        room.getId(),
                        room.getStatus(),
                        endedAt
                )
        );

        // 종료 직후 상태를 반환한다.
        // AI 후처리는 기존 스케줄러에서 진행한다.
        return new EndRoomResponse(
                room.getId(),
                room.getStatus().name(),
                endedAt,
                false,
                true
        );
    }

    // 강연방 소유자 확인 (조회용)
    private LectureRoom findOwnedRoom(
            Long roomId,
            Authentication authentication
    ) {
        User host = requireHost(authentication);

        LectureRoom room = lectureRoomRepository.findById(roomId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ROOM_NOT_FOUND)
                );

        if (!room.getHost().getId().equals(host.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        return room;
    }

    // 상태 변경 요청 시 강연방 행 잠금 및 소유자 확인
    private LectureRoom findOwnedRoomForUpdate(
            Long roomId,
            Authentication authentication
    ) {
        User host = requireHost(authentication);

        LectureRoom room = lectureRoomRepository.findForUpdate(roomId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ROOM_NOT_FOUND)
                );

        if (!room.getHost().getId().equals(host.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        return room;
    }

    // HOST 인증 확인
    private User requireHost(Authentication authentication) {

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {

            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }

        boolean isHost = authentication.getAuthorities()
                .stream()
                .anyMatch(authority ->
                        "ROLE_HOST".equals(authority.getAuthority())
                );

        if (!isHost) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.UNAUTHENTICATED)
                );
    }

    // 중복되지 않는 강연방 참여 코드 생성
    private String generateUniqueJoinCode() {

        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {

            StringBuilder code = new StringBuilder(JOIN_CODE_LENGTH);

            for (int i = 0; i < JOIN_CODE_LENGTH; i++) {
                int index = RANDOM.nextInt(
                        JOIN_CODE_CHARACTERS.length()
                );

                code.append(
                        JOIN_CODE_CHARACTERS.charAt(index)
                );
            }

            String joinCode = code.toString();

            if (!lectureRoomRepository.existsByJoinCode(joinCode)) {
                return joinCode;
            }
        }

        throw new BusinessException(ErrorCode.CONFLICT);
    }

    // DB LocalDateTime -> API Instant
    private Instant toInstant(LocalDateTime dateTime) {

        if (dateTime == null) {
            return null;
        }

        return dateTime.atZone(SEOUL).toInstant();
    }
}
