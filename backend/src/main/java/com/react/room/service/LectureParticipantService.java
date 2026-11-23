package com.react.room.service;

import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.global.idempotency.IdempotencyKeyValidator;
import com.react.global.idempotency.IdempotencyTokenGenerator;
import com.react.room.dto.JoinParticipantResponse;
import com.react.room.dto.ParticipantMeResponse;
import com.react.room.entity.LectureParticipant;
import com.react.room.entity.LectureRoom;
import com.react.room.repository.LectureParticipantRepository;
import com.react.room.repository.LectureRoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class LectureParticipantService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("[A-Za-z0-9_-]{43}");

    private final LectureRoomRepository roomRepository;
    private final LectureParticipantRepository participantRepository;
    private final ParticipantSessionPolicy sessionPolicy;
    private final ParticipantJoinWriteService joinWriteService;

    private final IdempotencyKeyValidator idempotencyKeyValidator;
    private final IdempotencyTokenGenerator idempotencyTokenGenerator;

    /**
     * 비회원 청중 입장 처리.
     *
     * 기존 방별 쿠키가 유효하면 같은 참여자를 반환한다.
     * 쿠키가 없는 최초 입장에서는 Idempotency-Key로 중복 생성을 방지한다.
     *
     * 이 메서드 바깥에는 @Transactional을 적용하지 않는다.
     * INSERT UNIQUE 충돌이 발생하면 쓰기 서비스의 트랜잭션이
     * 종료된 후 기존 참여자 정보를 다시 조회해야 하기 때문이다.
     *
     * 최초 저장의 방 행 잠금과 상태 검사는
     * ParticipantJoinWriteService에서 처리한다.
     */
    public JoinResult join(
            String joinCode,
            String nickname,
            String idempotencyKey,
            Map<Long, String> tokensByRoom
    ) {
        // 참여 코드는 강연방 식별자일 뿐, 개인 인증 수단이 아니다.
        LectureRoom room = roomRepository.findByJoinCode(joinCode)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.INVALID_JOIN_CODE)
                );

        // A, B 방마다 서로 다른 쿠키를 사용하므로 해당 방 토큰만 검사한다.
        Optional<LectureParticipant> existing = findValidParticipant(
                tokensByRoom.get(room.getId())
        );

        if (existing.isPresent()
                && existing.get().getLectureRoom().getId().equals(room.getId())) {

            // 동일 방의 재입장: 새 참여자를 만들거나 새 토큰을 발급하지 않는다.
            return new JoinResult(
                    toJoinResponse(existing.get()),
                    null,
                    false
            );
        }

        String validNickname = validateNickname(nickname);
        String validKey = idempotencyKeyValidator.validate(idempotencyKey);

        // 동일 강연방 + 동일 입장 키는 항상 동일한 인증 토큰을 생성한다.
        String scope = "participant-join:" + room.getId();

        String rawToken = idempotencyTokenGenerator.generate(
                scope,
                validKey,
                sessionPolicy.joinSecret()
        );

        String tokenHash = hashToken(rawToken);

        // 이미 처리된 같은 입장 시도는 종료 후에도 기존 참여자를 반환한다.
        Optional<LectureParticipant> previous =
                participantRepository.findByParticipantTokenHash(tokenHash);

        if (previous.isPresent()) {
            return repeatedJoin(
                    previous.get(),
                    room.getId(),
                    rawToken
            );
        }

        try {
            // 방 잠금, ENDED 검사, INSERT를 별도 트랜잭션에서 처리한다.
            ParticipantJoinWriteService.SaveResult result =
                    joinWriteService.saveIfAbsent(
                            room.getId(),
                            validNickname,
                            tokenHash
                    );

            if (!result.created()) {
                return repeatedJoin(
                        result.participant(),
                        room.getId(),
                        rawToken
                );
            }

            return new JoinResult(
                    toJoinResponse(result.participant()),
                    rawToken,
                    true
            );

        } catch (DataIntegrityViolationException e) {
            // UNIQUE 충돌이 발생해도 쓰기 트랜잭션은 이미 롤백되었다.
            // 따라서 별도 조회로 기존 참여자를 복원할 수 있다.
            return participantRepository.findByParticipantTokenHash(tokenHash)
                    .map(participant ->
                            repeatedJoin(
                                    participant,
                                    room.getId(),
                                    rawToken
                            )
                    )
                    .orElseThrow(() -> e);
        }
    }

    @Transactional(readOnly = true)
    public ParticipantMeResponse me(Long roomId, String rawToken) {
        LectureParticipant participant = findValidParticipant(rawToken)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.UNAUTHENTICATED)
                );

        // 유효한 다른 방 토큰이라도 현재 방에 사용할 수 없다.
        if (!participant.getLectureRoom().getId().equals(roomId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        return new ParticipantMeResponse(
                participant.getId(),
                participant.getLectureRoom().getId(),
                participant.getNickname(),
                toInstant(participant.getJoinedAt())
        );
    }

    private JoinResult repeatedJoin(
            LectureParticipant participant,
            Long roomId,
            String rawToken
    ) {
        if (!participant.getLectureRoom().getId().equals(roomId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        if (!isValid(participant)) {
            // 만료된 시도의 키는 새 입장에 재사용하지 않는다.
            throw new BusinessException(ErrorCode.CONFLICT);
        }

        return new JoinResult(
                toJoinResponse(participant),
                rawToken,
                false
        );
    }

    private Optional<LectureParticipant> findValidParticipant(String rawToken) {
        if (rawToken == null || !TOKEN_PATTERN.matcher(rawToken).matches()) {
            return Optional.empty();
        }

        return participantRepository
                .findByParticipantTokenHash(hashToken(rawToken))
                .filter(this::isValid);
    }

    private boolean isValid(LectureParticipant participant) {
        LocalDateTime validSince =
                LocalDateTime.now(SEOUL).minus(sessionPolicy.ttl());

        return !participant.getJoinedAt().isBefore(validSince);
    }

    private String validateNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_NICKNAME);
        }

        String value = nickname.strip();

        if (value.length() > 50) {
            throw new BusinessException(ErrorCode.INVALID_NICKNAME);
        }

        return value;
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(
                    digest.digest(
                            rawToken.getBytes(StandardCharsets.UTF_8)
                    )
            );

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 알고리즘을 사용할 수 없습니다.",
                    e
            );
        }
    }

    private JoinParticipantResponse toJoinResponse(
            LectureParticipant participant
    ) {
        LectureRoom room = participant.getLectureRoom();

        return new JoinParticipantResponse(
                participant.getId(),
                room.getId(),
                participant.getNickname(),
                room.getStatus().name(),
                toInstant(participant.getJoinedAt())
        );
    }

    private Instant toInstant(LocalDateTime time) {
        return time.atZone(SEOUL).toInstant();
    }

    /** rawToken은 서버 내부에서만 쿠키에 사용하고 응답 본문에는 넣지 않는다. */
    public record JoinResult(
            JoinParticipantResponse response,
            String rawToken,
            boolean created
    ) {
    }
}
