package com.react.room.service;

import com.react.auth.entity.User;
import com.react.auth.repository.UserRepository;
import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.room.entity.LectureRoom;
import com.react.room.dto.CreateRoomRequest;
import com.react.room.dto.CreateRoomResponse;
import com.react.room.dto.PublicRoomResponse;
import com.react.room.dto.RoomDetailResponse;
import com.react.room.dto.RoomInvitationResponse;
import com.react.room.repository.LectureRoomRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
public class LectureRoomService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String JOIN_CODE_CHARACTERS =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int JOIN_CODE_LENGTH = 12;
    private static final int MAX_CODE_ATTEMPTS = 10;

    private final LectureRoomRepository lectureRoomRepository;
    private final UserRepository userRepository;
    private final String joinBaseUrl;

    public LectureRoomService(
            LectureRoomRepository lectureRoomRepository,
            UserRepository userRepository,
            @Value("${ROOM_JOIN_BASE_URL:http://localhost:5173}") String joinBaseUrl
    ) {
        this.lectureRoomRepository = lectureRoomRepository;
        this.userRepository = userRepository;
        this.joinBaseUrl = joinBaseUrl;
    }

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

    @Transactional(readOnly = true)
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

    @Transactional(readOnly = true)
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

    @Transactional(readOnly = true)
    public PublicRoomResponse publicDetail(String joinCode) {

        LectureRoom room = lectureRoomRepository.findByJoinCode(joinCode)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.INVALID_JOIN_CODE)
                );

        boolean canJoin =
                room.getStatus() != LectureRoom.Status.ENDED;

        return new PublicRoomResponse(
                room.getId(),
                room.getTitle(),
                room.getDescription(),
                room.getStatus().name(),
                canJoin
        );
    }

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

    private String generateUniqueJoinCode() {

        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {

            StringBuilder code = new StringBuilder(JOIN_CODE_LENGTH);

            for (int i = 0; i < JOIN_CODE_LENGTH; i++) {
                int index = RANDOM.nextInt(JOIN_CODE_CHARACTERS.length());
                code.append(JOIN_CODE_CHARACTERS.charAt(index));
            }

            String joinCode = code.toString();

            if (!lectureRoomRepository.existsByJoinCode(joinCode)) {
                return joinCode;
            }
        }

        throw new BusinessException(ErrorCode.CONFLICT);
    }

    private Instant toInstant(LocalDateTime dateTime) {

        if (dateTime == null) {
            return null;
        }

        return dateTime.atZone(SEOUL).toInstant();
    }
}