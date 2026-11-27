package com.react.room;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.auth.entity.User;
import com.react.auth.repository.UserRepository;
import com.react.room.entity.LectureRoom;
import com.react.room.repository.LectureRoomRepository;
import com.react.room.service.LectureRoomExpiryScheduler;
import com.react.room.service.LectureRoomService;
import com.react.stt.dto.SttSessionView;
import com.react.stt.service.SttSessionService;
import jakarta.servlet.http.Cookie;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {
        "app.limits.max-live-rooms=6",
        "app.limits.max-live-rooms-per-host=1",
        "app.limits.max-participants-per-room=50",
        "app.limits.max-lecture-duration=180m"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoomOperationLimitsIntegrationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private UserRepository users;

    @Autowired
    private LectureRoomRepository rooms;

    @Autowired
    private LectureRoomService roomService;

    @Autowired
    private SttSessionService sttService;

    @Value("${app.limits.max-lecture-duration}")
    private Duration maxLectureDuration;

    private Flyway flyway;

    @BeforeEach
    void prepareDatabase() {
        // 테스트 전용 DB에서만 초기화를 허용한다.
        assertThat(jdbc.sql("SELECT DATABASE()")
                .query(String.class)
                .single())
                .endsWith("_test");

        flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();

        // 이전 테스트에서 생성된 LIVE 강연방의 영향을 제거한다.
        flyway.clean();
        flyway.migrate();
    }

    @AfterAll
    void cleanUp() {
        if (flyway != null) {
            // 다른 테스트에서 사용할 수 있도록 빈 스키마를 복원한다.
            flyway.clean();
            flyway.migrate();
        }
    }

    @Test
    void 전체_LIVE_강연방은_최대_6개까지만_허용한다()
            throws Exception {

        RoomRef firstRoom = null;

        for (int i = 0; i < 6; i++) {
            User host = createHost();
            RoomRef room = createRoom(host);

            assertThat(status(start(room))).isEqualTo(200);

            if (i == 0) {
                firstRoom = room;
            }
        }

        assertThat(liveRoomCount()).isEqualTo(6L);

        RoomRef seventhRoom = createRoom(createHost());

        assertConflict(
                start(seventhRoom),
                "LIVE_ROOM_LIMIT_EXCEEDED"
        );

        assertThat(roomStatus(seventhRoom)).isEqualTo("READY");
        assertThat(liveRoomCount()).isEqualTo(6L);

        // 기존 강연 하나가 종료되면 새로운 강연을 시작할 수 있다.
        assertThat(status(end(firstRoom))).isEqualTo(200);
        assertThat(liveRoomCount()).isEqualTo(5L);

        assertThat(status(start(seventhRoom))).isEqualTo(200);
        assertThat(liveRoomCount()).isEqualTo(6L);
    }

    @Test
    void 강연자는_동시에_하나의_강연방만_진행할_수_있다()
            throws Exception {

        User host = createHost();

        RoomRef firstRoom = createRoom(host);
        RoomRef secondRoom = createRoom(host);

        assertThat(status(start(firstRoom))).isEqualTo(200);

        assertConflict(
                start(secondRoom),
                "HOST_LIVE_ROOM_LIMIT_EXCEEDED"
        );

        assertThat(roomStatus(firstRoom)).isEqualTo("LIVE");
        assertThat(roomStatus(secondRoom)).isEqualTo("READY");
        assertThat(hostLiveRoomCount(host)).isEqualTo(1L);

        assertThat(status(end(firstRoom))).isEqualTo(200);

        assertThat(status(start(secondRoom))).isEqualTo(200);
        assertThat(hostLiveRoomCount(host)).isEqualTo(1L);
    }

    @Test
    void 강연방의_신규_청중은_최대_50명까지만_입장할_수_있다()
            throws Exception {

        RoomRef room = createRoom(createHost());

        // READY 상태에서도 청중 정원을 제한한다.
        for (int i = 1; i <= 50; i++) {
            MvcResult result = join(room, "청중" + i);

            assertThat(status(result)).isEqualTo(201);
        }

        assertThat(participantCount(room)).isEqualTo(50L);

        assertConflict(
                join(room, "51번째 청중"),
                "ROOM_PARTICIPANT_LIMIT_EXCEEDED"
        );

        assertThat(participantCount(room)).isEqualTo(50L);
    }

    @Test
    void 정원이_가득_차도_기존_청중은_재입장할_수_있다()
            throws Exception {

        RoomRef room = createRoom(createHost());

        assertThat(status(start(room))).isEqualTo(200);

        MvcResult first = join(room, "기존 청중");

        assertThat(status(first)).isEqualTo(201);

        long participantId = data(first)
                .path("participantId")
                .asLong();

        Cookie participantCookie = cookieFrom(first, room);

        for (int i = 2; i <= 50; i++) {
            assertThat(status(join(room, "청중" + i)))
                    .isEqualTo(201);
        }

        assertThat(participantCount(room)).isEqualTo(50L);

        MvcResult repeated = joinWithKey(
                room,
                "기존 청중",
                UUID.randomUUID().toString(),
                participantCookie
        );

        assertThat(status(repeated)).isEqualTo(200);

        assertThat(data(repeated).path("participantId").asLong())
                .isEqualTo(participantId);

        assertThat(participantCount(room)).isEqualTo(50L);
    }

    @Test
    void 정원이_가득_차도_동일한_입장_키는_기존_청중을_반환한다()
            throws Exception {

        RoomRef room = createRoom(createHost());

        String idempotencyKey = UUID.randomUUID().toString();

        MvcResult first = joinWithKey(
                room,
                "기존 청중",
                idempotencyKey
        );

        assertThat(status(first)).isEqualTo(201);

        for (int i = 2; i <= 50; i++) {
            assertThat(status(join(room, "청중" + i)))
                    .isEqualTo(201);
        }

        MvcResult repeated = joinWithKey(
                room,
                "기존 청중",
                idempotencyKey
        );

        assertThat(status(repeated)).isEqualTo(200);

        assertThat(data(repeated).path("participantId").asLong())
                .isEqualTo(data(first).path("participantId").asLong());

        assertThat(cookieFrom(repeated, room).getValue())
                .isEqualTo(cookieFrom(first, room).getValue());

        assertThat(participantCount(room)).isEqualTo(50L);
    }

    @Test
    void 여러_강연방이_동시에_시작해도_전체_6개를_초과하지_않는다()
            throws Exception {

        for (int i = 0; i < 5; i++) {
            RoomRef room = createRoom(createHost());

            assertThat(status(start(room))).isEqualTo(200);
        }

        assertThat(liveRoomCount()).isEqualTo(5L);

        RoomRef firstRoom = createRoom(createHost());
        RoomRef secondRoom = createRoom(createHost());

        List<MvcResult> results = concurrently(
                () -> start(firstRoom),
                () -> start(secondRoom)
        );

        assertThat(results)
                .extracting(this::status)
                .containsExactlyInAnyOrder(200, 409);

        assertConflict(
                rejectedResult(results),
                "LIVE_ROOM_LIMIT_EXCEEDED"
        );

        assertThat(liveRoomCount()).isEqualTo(6L);

        assertThat(List.of(
                roomStatus(firstRoom),
                roomStatus(secondRoom)
        )).containsExactlyInAnyOrder("LIVE", "READY");
    }

    @Test
    void 동일_강연자가_두_강연방을_동시에_시작해도_하나만_성공한다()
            throws Exception {

        User host = createHost();

        RoomRef firstRoom = createRoom(host);
        RoomRef secondRoom = createRoom(host);

        List<MvcResult> results = concurrently(
                () -> start(firstRoom),
                () -> start(secondRoom)
        );

        assertThat(results)
                .extracting(this::status)
                .containsExactlyInAnyOrder(200, 409);

        assertConflict(
                rejectedResult(results),
                "HOST_LIVE_ROOM_LIMIT_EXCEEDED"
        );

        assertThat(hostLiveRoomCount(host)).isEqualTo(1L);

        assertThat(List.of(
                roomStatus(firstRoom),
                roomStatus(secondRoom)
        )).containsExactlyInAnyOrder("LIVE", "READY");
    }

    @Test
    void 청중이_동시에_입장해도_정원_50명을_초과하지_않는다()
            throws Exception {

        RoomRef room = createRoom(createHost());

        assertThat(status(start(room))).isEqualTo(200);

        for (int i = 1; i <= 49; i++) {
            assertThat(status(join(room, "청중" + i)))
                    .isEqualTo(201);
        }

        assertThat(participantCount(room)).isEqualTo(49L);

        List<MvcResult> results = concurrently(
                () -> join(room, "동시 입장 A"),
                () -> join(room, "동시 입장 B")
        );

        assertThat(results)
                .extracting(this::status)
                .containsExactlyInAnyOrder(201, 409);

        assertConflict(
                rejectedResult(results),
                "ROOM_PARTICIPANT_LIMIT_EXCEEDED"
        );

        assertThat(participantCount(room)).isEqualTo(50L);
    }

    @Test
    void 강연_시간이_180분에_도달하기_전에는_자동_종료하지_않는다()
            throws Exception {

        RoomRef room = createRoom(createHost());

        assertThat(status(start(room))).isEqualTo(200);

        assertThat(maxLectureDuration)
                .isEqualTo(Duration.ofMinutes(180));

        LocalDateTime now = LocalDateTime.now(SEOUL)
                .truncatedTo(ChronoUnit.SECONDS);

        LocalDateTime cutoff = now.minus(maxLectureDuration);

        // 강연 시간이 179분 59초인 상황
        changeStartedAt(room, cutoff.plusSeconds(1));

        assertThat(roomService.findExpiredRoomIds(cutoff))
                .doesNotContain(room.id());

        assertThat(roomService.endExpiredRoom(
                room.id(),
                cutoff
        )).isFalse();

        assertThat(roomStatus(room)).isEqualTo("LIVE");
    }

    @Test
    void 강연_시간이_180분에_도달하면_자동_종료한다()
            throws Exception {

        RoomRef room = createRoom(createHost());

        assertThat(status(start(room))).isEqualTo(200);

        LocalDateTime now = LocalDateTime.now(SEOUL)
                .truncatedTo(ChronoUnit.SECONDS);

        LocalDateTime cutoff = now.minus(maxLectureDuration);

        // 정확히 180분이 경과한 상황
        changeStartedAt(room, cutoff);

        assertThat(roomService.findExpiredRoomIds(cutoff))
                .contains(room.id());

        assertThat(roomService.endExpiredRoom(
                room.id(),
                cutoff
        )).isTrue();

        assertThat(roomStatus(room)).isEqualTo("ENDED");
        assertThat(endedAt(room)).isNotNull();
        assertThat(liveRoomCount()).isZero();
    }

    @Test
    void 자동_종료시_기존_투표와_STT도_정상적으로_종료된다()
            throws Exception {

        RoomRef expiredRoom = createRoom(createHost());
        RoomRef activeRoom = createRoom(createHost());

        assertThat(status(start(expiredRoom))).isEqualTo(200);
        assertThat(status(start(activeRoom))).isEqualTo(200);

        LocalDateTime now = LocalDateTime.now(SEOUL);

        changeStartedAt(
                expiredRoom,
                now.minus(maxLectureDuration).minusSeconds(10)
        );

        // 종료 대상 방에 진행 중인 투표 생성
        jdbc.sql("""
                INSERT INTO polls (
                    lecture_room_id,
                    question,
                    status,
                    created_at,
                    closes_at
                )
                VALUES (?, '진행 중인 투표', 'OPEN', ?, ?)
                """)
                .params(
                        expiredRoom.id(),
                        now.minusSeconds(10),
                        now.plusMinutes(2)
                )
                .update();

        // 실제 STT 서비스에서 녹음 세션 시작
        UUID key = UUID.randomUUID();

        SttSessionView recording = sttService.start(
                expiredRoom.id(),
                expiredRoom.hostEmail(),
                key
        ).session();

        assertThat(recording.status()).isEqualTo("RECORDING");

        // test 프로파일에서는 자동 스케줄링이 비활성화되므로
        // 실제 서비스를 연결하여 스케줄러 메서드를 직접 실행한다.
        LectureRoomExpiryScheduler scheduler =
                new LectureRoomExpiryScheduler(
                        roomService,
                        maxLectureDuration
                );

        scheduler.expireRooms();

        assertThat(roomStatus(expiredRoom)).isEqualTo("ENDED");
        assertThat(roomStatus(activeRoom)).isEqualTo("LIVE");

        assertThat(jdbc.sql("""
                SELECT status
                FROM polls
                WHERE lecture_room_id = ?
                """)
                .param(expiredRoom.id())
                .query(String.class)
                .single())
                .isEqualTo("CLOSED");

        assertThat(jdbc.sql("""
                SELECT close_reason
                FROM polls
                WHERE lecture_room_id = ?
                """)
                .param(expiredRoom.id())
                .query(String.class)
                .single())
                .isEqualTo("ROOM_ENDED");

        SttSessionView stopped = sttService.start(
                expiredRoom.id(),
                expiredRoom.hostEmail(),
                key
        ).session();

        assertThat(stopped.sttSessionId())
                .isEqualTo(recording.sttSessionId());

        assertThat(stopped.status()).isEqualTo("STOPPED");

        assertThat(stopped.endedAt())
                .isEqualTo(
                        endedAt(expiredRoom)
                                .atZone(SEOUL)
                                .toInstant()
                );
    }

    @Test
    void 수동_종료와_자동_종료가_동시에_실행되어도_한_번만_종료된다()
            throws Exception {

        RoomRef room = createRoom(createHost());

        assertThat(status(start(room))).isEqualTo(200);

        LocalDateTime cutoff = LocalDateTime.now(SEOUL)
                .minus(maxLectureDuration);

        changeStartedAt(room, cutoff.minusSeconds(10));

        List<Integer> results = concurrently(
                () -> status(end(room)),
                () -> roomService.endExpiredRoom(room.id(), cutoff)
                        ? 200
                        : 409
        );

        // 수동 종료 또는 자동 종료 중 하나만 실제 상태를 변경한다.
        assertThat(results)
                .containsExactlyInAnyOrder(200, 409);

        assertThat(roomStatus(room)).isEqualTo("ENDED");

        LocalDateTime firstEndedAt = endedAt(room);

        assertThat(firstEndedAt).isNotNull();

        // 이미 종료된 강연은 다시 자동 종료하지 않는다.
        assertThat(roomService.endExpiredRoom(
                room.id(),
                cutoff
        )).isFalse();

        assertThat(endedAt(room)).isEqualTo(firstEndedAt);
        assertThat(liveRoomCount()).isZero();
    }

    private User createHost() {
        return users.saveAndFlush(
                new User(
                        UUID.randomUUID() + "@example.com",
                        "hash",
                        "테스트 강연자"
                )
        );
    }

    private RoomRef createRoom(User host) {

        String joinCode = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 12)
                .toUpperCase();

        LectureRoom room = rooms.saveAndFlush(
                new LectureRoom(
                        host,
                        "운영 제한 테스트",
                        "강연방 운영 제한 검증",
                        joinCode
                )
        );

        return new RoomRef(
                room.getId(),
                room.getJoinCode(),
                host.getEmail()
        );
    }

    private MvcResult start(RoomRef room) throws Exception {

        return mvc.perform(
                post("/api/v1/rooms/{roomId}/start", room.id())
                        .with(user(room.hostEmail()).roles("HOST"))
                        .with(csrf())
        ).andReturn();
    }

    private MvcResult end(RoomRef room) throws Exception {

        return mvc.perform(
                post("/api/v1/rooms/{roomId}/end", room.id())
                        .with(user(room.hostEmail()).roles("HOST"))
                        .with(csrf())
        ).andReturn();
    }

    private MvcResult join(
            RoomRef room,
            String nickname
    ) throws Exception {

        return joinWithKey(
                room,
                nickname,
                UUID.randomUUID().toString()
        );
    }

    private MvcResult joinWithKey(
            RoomRef room,
            String nickname,
            String idempotencyKey,
            Cookie... cookies
    ) throws Exception {

        MockHttpServletRequestBuilder request = post(
                "/api/v1/public/rooms/{joinCode}/participants",
                room.joinCode()
        )
                .with(csrf())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                        Map.of("nickname", nickname)
                ));

        if (cookies.length > 0) {
            request.cookie(cookies);
        }

        return mvc.perform(request).andReturn();
    }

    private int status(MvcResult result) {

        return result.getResponse().getStatus();
    }

    private JsonNode data(MvcResult result) throws Exception {

        return json.readTree(
                result.getResponse().getContentAsString()
        ).path("data");
    }

    private void assertConflict(
            MvcResult result,
            String expectedCode
    ) throws Exception {

        assertThat(status(result)).isEqualTo(409);

        JsonNode response = json.readTree(
                result.getResponse().getContentAsString()
        );

        assertThat(response.path("code").asText())
                .isEqualTo(expectedCode);
    }

    private MvcResult rejectedResult(List<MvcResult> results) {

        return results.stream()
                .filter(result -> status(result) == 409)
                .findFirst()
                .orElseThrow();
    }

    private Cookie cookieFrom(
            MvcResult result,
            RoomRef room
    ) {

        String header = result.getResponse()
                .getHeader(HttpHeaders.SET_COOKIE);

        assertThat(header).isNotNull();

        String cookieName = "PARTICIPANT_SESSION_" + room.id();
        String prefix = cookieName + "=";
        String firstPart = header.split(";", 2)[0];

        assertThat(firstPart).startsWith(prefix);

        return new Cookie(
                cookieName,
                firstPart.substring(prefix.length())
        );
    }

    private long liveRoomCount() {

        return jdbc.sql("""
                SELECT COUNT(*)
                FROM lecture_rooms
                WHERE status = 'LIVE'
                """)
                .query(Long.class)
                .single();
    }

    private long hostLiveRoomCount(User host) {

        return jdbc.sql("""
                SELECT COUNT(*)
                FROM lecture_rooms
                WHERE host_user_id = ?
                    AND status = 'LIVE'
                """)
                .param(host.getId())
                .query(Long.class)
                .single();
    }

    private long participantCount(RoomRef room) {

        return jdbc.sql("""
                SELECT COUNT(*)
                FROM lecture_participants
                WHERE lecture_room_id = ?
                """)
                .param(room.id())
                .query(Long.class)
                .single();
    }

    private String roomStatus(RoomRef room) {

        return jdbc.sql("""
                SELECT status
                FROM lecture_rooms
                WHERE id = ?
                """)
                .param(room.id())
                .query(String.class)
                .single();
    }

    private LocalDateTime endedAt(RoomRef room) {

        return jdbc.sql("""
                SELECT ended_at
                FROM lecture_rooms
                WHERE id = ?
                """)
                .param(room.id())
                .query(LocalDateTime.class)
                .single();
    }

    private void changeStartedAt(
            RoomRef room,
            LocalDateTime startedAt
    ) {

        // 실제로 180분을 기다리지 않고 시작 시간을 변경한다.
        jdbc.sql("""
                UPDATE lecture_rooms
                SET started_at = ?
                WHERE id = ?
                """)
                .params(startedAt, room.id())
                .update();
    }

    private <T> List<T> concurrently(
            Callable<T> first,
            Callable<T> second
    ) throws Exception {

        CyclicBarrier gate = new CyclicBarrier(2);

        try (ExecutorService executor =
                     Executors.newFixedThreadPool(2)) {

            Future<T> firstResult = executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return first.call();
            });

            Future<T> secondResult = executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return second.call();
            });

            return List.of(
                    firstResult.get(30, TimeUnit.SECONDS),
                    secondResult.get(30, TimeUnit.SECONDS)
            );
        }
    }

    private record RoomRef(
            Long id,
            String joinCode,
            String hostEmail
    ) {
    }
}