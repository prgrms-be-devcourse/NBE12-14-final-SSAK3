package com.react.global.websocket;

import com.react.auth.entity.User;
import com.react.auth.repository.UserRepository;
import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.room.dto.ParticipantMeResponse;
import com.react.room.entity.LectureRoom;
import com.react.room.repository.LectureRoomRepository;
import com.react.room.service.LectureParticipantService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 방 하나에 대해 요청자가 강연자인지 청중인지, 아니면 아무도 아닌지를 올바르게 가려내는지 확인한다. */
class StompActorResolverTest {
    private static final long ROOM = 10L;
    private static final long HOST_ID = 1L;
    private static final String HOST_EMAIL = "host@example.com";

    private final UserRepository users = mock(UserRepository.class);
    private final LectureRoomRepository rooms = mock(LectureRoomRepository.class);
    private final LectureParticipantService participants = mock(LectureParticipantService.class);
    private final StompActorResolver resolver = new StompActorResolver(users, rooms, participants);

    @Test
    void 방_주인인_강연자는_HOST_로_판별한다() {
        hostOwnsRoom(ROOM, HOST_ID);

        StompActor actor = resolver.resolve(ROOM, host(HOST_EMAIL), Map.of());

        assertThat(actor).isEqualTo(new StompActor(StompActor.Type.HOST, HOST_ID));
    }

    @Test
    void 다른_강연자의_방이면_403_이다() {
        when(users.findByEmail(HOST_EMAIL)).thenReturn(Optional.of(user(HOST_ID)));
        LectureRoom otherHostsRoom = room(99L);
        when(rooms.findById(ROOM)).thenReturn(Optional.of(otherHostsRoom));

        assertCode(ErrorCode.FORBIDDEN, () -> resolver.resolve(ROOM, host(HOST_EMAIL), Map.of()));
    }

    @Test
    void 그_방의_유효한_쿠키는_PARTICIPANT_로_판별한다() {
        when(participants.me(ROOM, "token")).thenReturn(me(7L, ROOM));

        StompActor actor = resolver.resolve(ROOM, null, tokens(Map.of(ROOM, "token")));

        assertThat(actor).isEqualTo(new StompActor(StompActor.Type.PARTICIPANT, 7L));
    }

    @Test
    void 다른_방의_토큰을_이_방_쿠키로_보내면_403_이다() {
        when(participants.me(ROOM, "forged"))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

        assertCode(ErrorCode.FORBIDDEN, () -> resolver.resolve(ROOM, null, tokens(Map.of(ROOM, "forged"))));
    }

    @Test
    void 만료되었거나_잘못된_쿠키는_401_이다() {
        when(participants.me(ROOM, "expired"))
                .thenThrow(new BusinessException(ErrorCode.UNAUTHENTICATED));

        assertCode(ErrorCode.UNAUTHENTICATED, () -> resolver.resolve(ROOM, null, tokens(Map.of(ROOM, "expired"))));
    }

    @Test
    void 이_방의_쿠키가_없으면_다른_방_쿠키가_있어도_401_이고_검증을_시도하지_않는다() {
        assertCode(ErrorCode.UNAUTHENTICATED, () -> resolver.resolve(ROOM, null, tokens(Map.of(99L, "other-room"))));

        verify(participants, never()).me(anyLong(), any());
    }

    @Test
    void 로그인도_쿠키도_없으면_401_이다() {
        assertCode(ErrorCode.UNAUTHENTICATED, () -> resolver.resolve(ROOM, null, Map.of()));
        assertCode(ErrorCode.UNAUTHENTICATED, () -> resolver.resolve(ROOM, anonymous(), Map.of()));
    }

    @Test
    void HOST_역할이_아닌_로그인은_강연자로_보지_않는다() {
        Principal notHost = new UsernamePasswordAuthenticationToken(HOST_EMAIL, null,
                List.of(new SimpleGrantedAuthority("ROLE_PARTICIPANT")));

        assertCode(ErrorCode.UNAUTHENTICATED, () -> resolver.resolve(ROOM, notHost, Map.of()));

        verify(users, never()).findByEmail(eq(HOST_EMAIL));
    }

    private void hostOwnsRoom(long roomId, long hostId) {
        when(users.findByEmail(HOST_EMAIL)).thenReturn(Optional.of(user(hostId)));
        LectureRoom room = room(hostId);
        when(rooms.findById(roomId)).thenReturn(Optional.of(room));
    }

    private static Map<String, Object> tokens(Map<Long, String> byRoom) {
        return Map.of(StompHandshakeInterceptor.PARTICIPANT_TOKENS, byRoom);
    }

    private static Principal host(String email) {
        return new UsernamePasswordAuthenticationToken(email, null, List.of(new SimpleGrantedAuthority("ROLE_HOST")));
    }

    private static Principal anonymous() {
        return new AnonymousAuthenticationToken("key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
    }

    private static ParticipantMeResponse me(long participantId, long roomId) {
        return new ParticipantMeResponse(participantId, roomId, "청중", Instant.now());
    }

    private static User user(long id) {
        User user = new User(HOST_EMAIL, "hash", "강연자");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static LectureRoom room(long hostId) {
        LectureRoom room = mock(LectureRoom.class);
        when(room.getHost()).thenReturn(user(hostId));
        return room;
    }

    private static void assertCode(ErrorCode expected, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}
