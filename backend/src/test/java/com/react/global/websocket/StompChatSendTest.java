package com.react.global.websocket;

import com.react.auth.entity.User;
import com.react.global.exception.ErrorCode;
import com.react.room.entity.LectureRoom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompSession;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 채팅 전송: 작성자는 쿠키·로그인으로 서버가 정하고, 본문에서는 content 만 읽어 저장한다. */
class StompChatSendTest extends StompIntegrationTestSupport {
    private String hostEmail;
    private User host;
    private LectureRoom room;

    @BeforeEach
    void prepare() {
        hostEmail = "host-" + UUID.randomUUID() + "@example.com";
        host = newHost(hostEmail);
        room = liveRoom();
    }

    @Test
    void 청중이_보낸_메시지는_그_청중이_작성자로_저장된다() throws Exception {
        var joined = participants.join(room.getJoinCode(), "청중A", UUID.randomUUID().toString(), Map.of());
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN,
                "PARTICIPANT_SESSION_" + room.getId() + "=" + joined.rawToken());

        session.send(chatDestination(), Map.of("content", "Redis 가 궁금해요"));

        Map<String, Object> row = awaitSavedMessage();
        assertThat(row.get("content")).isEqualTo("Redis 가 궁금해요");
        assertThat(((Number) row.get("participant_id")).longValue()).isEqualTo(joined.response().participantId());
        assertThat(row.get("host_user_id")).isNull();
        session.disconnect();
    }

    @Test
    void 강연자가_보낸_메시지는_그_강연자가_작성자로_저장된다() throws Exception {
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN, loginCookies(hostEmail));

        session.send(chatDestination(), Map.of("content", "질문 주세요"));

        Map<String, Object> row = awaitSavedMessage();
        assertThat(row.get("content")).isEqualTo("질문 주세요");
        assertThat(((Number) row.get("host_user_id")).longValue()).isEqualTo(host.getId());
        assertThat(row.get("participant_id")).isNull();
        session.disconnect();
    }

    @Test
    void 본문에_가짜_작성자를_적어도_무시하고_쿠키로_확인한_사람이_작성자다() throws Exception {
        var joined = participants.join(room.getJoinCode(), "청중A", UUID.randomUUID().toString(), Map.of());
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN,
                "PARTICIPANT_SESSION_" + room.getId() + "=" + joined.rawToken());

        // 청중이 강연자인 척하는 필드와 다른 참여자 번호를 함께 보낸다.
        session.send(chatDestination(), Map.of(
                "content", "가짜 작성자 시도",
                "hostUserId", host.getId(),
                "participantId", 999999,
                "sender", Map.of("type", "HOST", "id", host.getId())));

        Map<String, Object> row = awaitSavedMessage();
        assertThat(((Number) row.get("participant_id")).longValue()).isEqualTo(joined.response().participantId());
        assertThat(row.get("host_user_id")).isNull();
        session.disconnect();
    }

    @Test
    void 한_명이_보내면_같은_방_구독자_전원이_받는다() throws Exception {
        var a = join("청중A");
        var b = join("청중B");
        StompSession sessionA = client.connect(StompTestClient.ALLOWED_ORIGIN, a.cookie());
        StompSession sessionB = client.connect(StompTestClient.ALLOWED_ORIGIN, b.cookie());
        StompSession hostSession = client.connect(StompTestClient.ALLOWED_ORIGIN, loginCookies(hostEmail));
        var queueA = subscribeReady(sessionA, room);
        var queueB = subscribeReady(sessionB, room);
        var queueHost = subscribeReady(hostSession, room);

        sessionA.send(chatDestination(), Map.of("content", "안녕하세요"));

        // 보낸 사람을 포함해 같은 방 구독자 모두가 받는다.
        for (var queue : java.util.List.of(queueA, queueB, queueHost)) {
            Map<String, Object> event = StompTestClient.nextOfType(queue, "CHAT_CREATED", 5000);
            assertThat(event).isNotNull();
            assertThat(data(event).get("content")).isEqualTo("안녕하세요");
        }
        sessionA.disconnect();
        sessionB.disconnect();
        hostSession.disconnect();
    }

    @Test
    void 다른_방_구독자는_방송을_받지_못한다() throws Exception {
        LectureRoom otherRoom = liveRoom();
        var a = join("청중A");
        var other = join(otherRoom, "다른방청중");
        StompSession sessionA = client.connect(StompTestClient.ALLOWED_ORIGIN, a.cookie());
        StompSession sessionOther = client.connect(StompTestClient.ALLOWED_ORIGIN, other.cookie());
        var queueA = subscribeReady(sessionA, room);
        var queueOther = subscribeReady(sessionOther, otherRoom);

        sessionA.send(chatDestination(), Map.of("content", "우리 방 이야기"));

        // 우리 방에는 방송이 갔고(= 방송이 실제로 일어났고), 다른 방에는 오지 않는다.
        assertThat(StompTestClient.nextOfType(queueA, "CHAT_CREATED", 5000)).isNotNull();
        assertThat(StompTestClient.nextOfType(queueOther, "CHAT_CREATED", 1000)).isNull();
        sessionA.disconnect();
        sessionOther.disconnect();
    }

    @Test
    void 방송은_명세의_CHAT_CREATED_모양이다() throws Exception {
        var a = join("청중A");
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN, a.cookie());
        var queue = subscribeReady(session, room);

        session.send(chatDestination(), Map.of("content", "모양 확인"));

        Map<String, Object> data = data(StompTestClient.nextOfType(queue, "CHAT_CREATED", 5000));
        assertThat(((Number) data.get("messageId")).longValue()).isPositive();
        assertThat(((Number) data.get("roomId")).longValue()).isEqualTo(room.getId());
        assertThat(data.get("content")).isEqualTo("모양 확인");
        assertThat(data.get("sender")).isEqualTo(Map.of("type", "PARTICIPANT", "id", (int) a.participantId(), "name", "청중A"));
        // 시각은 숫자가 아니라 명세처럼 ISO-8601 UTC 문자열이어야 한다.
        assertThat(data.get("receivedAt")).isInstanceOf(String.class);
        assertThat(java.time.Instant.parse((String) data.get("receivedAt"))).isNotNull();
        session.disconnect();
    }

    @Test
    void 오류는_보낸_사람에게만_가고_다른_사람에게는_새지_않는다() throws Exception {
        LectureRoom readyRoom = newRoom(host, "준비 중");
        var a = join(readyRoom, "청중A");   // LIVE 가 아닌 방의 청중: ROOM_NOT_LIVE 를 받는다
        var b = join("청중B");              // LIVE 방의 청중: 자기 오류(INVALID_INPUT)만 받는다
        StompSession sessionA = client.connect(StompTestClient.ALLOWED_ORIGIN, a.cookie());
        StompSession sessionB = client.connect(StompTestClient.ALLOWED_ORIGIN, b.cookie());
        var errorsA = client.subscribe(sessionA, "/user/queue/errors");
        var errorsB = client.subscribe(sessionB, "/user/queue/errors");
        // B 의 오류 우편함이 등록되었는지는 B 가 자기 오류를 받아 보는 것으로 확인한다.
        assertThat(StompTestClient.awaitBroadcast(errorsB,
                () -> sessionB.send(chatDestination(), Map.of("content", " ")))).containsEntry("code", "INVALID_INPUT");

        Map<String, Object> error = StompTestClient.awaitBroadcast(errorsA,
                () -> sessionA.send("/app/rooms/" + readyRoom.getId() + "/chat", Map.of("content", "안녕")));

        assertThat(error).containsEntry("type", "ERROR").containsEntry("code", "ROOM_NOT_LIVE")
                .containsEntry("message", ErrorCode.ROOM_NOT_LIVE.getMessage());
        // A 의 오류(ROOM_NOT_LIVE)는 B 에게 오지 않는다.
        assertThat(StompTestClient.nextOfType(errorsB, "ERROR", 500)).satisfiesAnyOf(
                leaked -> assertThat(leaked).isNull(),
                leaked -> assertThat(leaked.get("code")).isEqualTo("INVALID_INPUT"));
        sessionA.disconnect();
        sessionB.disconnect();
    }

    @Test
    void 빈_메시지는_거절되고_저장도_방송도_되지_않는다() throws Exception {
        var a = join("청중A");
        var b = join("청중B");
        StompSession sessionA = client.connect(StompTestClient.ALLOWED_ORIGIN, a.cookie());
        StompSession sessionB = client.connect(StompTestClient.ALLOWED_ORIGIN, b.cookie());
        var roomQueue = subscribeReady(sessionB, room);

        expectError(sessionA, chatDestination(), Map.of("content", "   "), ErrorCode.INVALID_INPUT);

        assertThat(savedCount(room)).isZero();
        assertThat(StompTestClient.nextOfType(roomQueue, "CHAT_CREATED", 300)).isNull();
        sessionA.disconnect();
        sessionB.disconnect();
    }

    @Test
    void 천자를_넘는_메시지는_거절된다() throws Exception {
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN, join("청중A").cookie());

        expectError(session, chatDestination(), Map.of("content", "가".repeat(1001)), ErrorCode.INVALID_INPUT);

        assertThat(savedCount(room)).isZero();
        session.disconnect();
    }

    @Test
    void 준비_중이거나_종료된_방에는_보낼_수_없다() throws Exception {
        LectureRoom readyRoom = newRoom(host, "준비 중");
        LectureRoom endedRoom = liveRoom();
        StompSession readySession = client.connect(StompTestClient.ALLOWED_ORIGIN, join(readyRoom, "청중A").cookie());
        // 종료된 방에는 새로 입장할 수 없으므로, 입장한 뒤에 방을 종료 상태로 바꾼다.
        StompSession endedSession = client.connect(StompTestClient.ALLOWED_ORIGIN, join(endedRoom, "청중B").cookie());
        jdbc.sql("UPDATE lecture_rooms SET status = 'ENDED' WHERE id = ?").param(endedRoom.getId()).update();

        expectError(readySession, "/app/rooms/" + readyRoom.getId() + "/chat", Map.of("content", "안녕"),
                ErrorCode.ROOM_NOT_LIVE);
        expectError(endedSession, "/app/rooms/" + endedRoom.getId() + "/chat", Map.of("content", "안녕"),
                ErrorCode.ROOM_ENDED);

        assertThat(savedCount(readyRoom)).isZero();
        assertThat(savedCount(endedRoom)).isZero();
        readySession.disconnect();
        endedSession.disconnect();
    }

    @Test
    void 다른_방_토큰을_이_방_쿠키로_위조하면_거절된다() throws Exception {
        LectureRoom otherRoom = liveRoom();
        String otherToken = join(otherRoom, "다른방청중").cookie().split("=", 2)[1];
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN,
                "PARTICIPANT_SESSION_" + room.getId() + "=" + otherToken);

        expectError(session, chatDestination(), Map.of("content", "위조"), ErrorCode.FORBIDDEN);

        assertThat(savedCount(room)).isZero();
        session.disconnect();
    }

    @Test
    void 쿠키도_로그인도_없으면_전송이_거절된다() throws Exception {
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN);

        expectError(session, chatDestination(), Map.of("content", "누구세요"), ErrorCode.UNAUTHENTICATED);

        assertThat(savedCount(room)).isZero();
        session.disconnect();
    }

    @Test
    void 본문이_객체가_아니면_거절된다() throws Exception {
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN, join("청중A").cookie());

        expectError(session, chatDestination(), java.util.List.of(1, 2, 3), ErrorCode.INVALID_INPUT);

        assertThat(savedCount(room)).isZero();
        session.disconnect();
    }

    @Test
    void 방송_주소로_직접_전송하면_거절되고_아무에게도_가지_않는다() throws Exception {
        var attacker = join("청중A");
        var listener = join("청중B");
        StompSession attackerSession = client.connect(StompTestClient.ALLOWED_ORIGIN, attacker.cookie());
        StompSession listenerSession = client.connect(StompTestClient.ALLOWED_ORIGIN, listener.cookie());
        var queue = subscribeReady(listenerSession, room);

        // 서버를 거치지 않고, 강연자가 보낸 것처럼 꾸민 CHAT_CREATED 를 방송 주소에 직접 보낸다.
        attackerSession.send("/topic/rooms/" + room.getId() + "/chat", Map.of("type", "CHAT_CREATED",
                "data", Map.of("content", "가짜 방송", "sender", Map.of("type", "HOST", "id", host.getId(), "name", "강연자"))));

        assertThat(StompTestClient.awaitClosed(attackerSession)).isTrue();
        assertThat(StompTestClient.nextOfType(queue, "CHAT_CREATED", 500)).isNull();
        assertThat(savedCount(room)).isZero();
        listenerSession.disconnect();
    }


    /** 오류 우편함을 구독한 뒤, 거절될 메시지를 오류가 올 때까지 반복해 보내고 받은 오류 코드를 확인한다. */
    private void expectError(StompSession session, String destination, Object payload, ErrorCode expected)
            throws InterruptedException {
        var errors = client.subscribe(session, "/user/queue/errors");

        Map<String, Object> error = StompTestClient.awaitBroadcast(errors, () -> session.send(destination, payload));

        assertThat(error).as("오류가 보낸 사람에게 도착해야 한다").isNotNull();
        assertThat(error).containsEntry("type", "ERROR")
                .containsEntry("code", expected.name())
                .containsEntry("message", expected.getMessage());
    }

    private long savedCount(LectureRoom target) {
        return jdbc.sql("SELECT COUNT(*) FROM chat_messages WHERE lecture_room_id = ?")
                .param(target.getId()).query(Long.class).single();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> event) {
        return (Map<String, Object>) event.get("data");
    }

    /** 구독한 뒤, 서버가 그 구독을 등록해 방송이 닿는 것을 워밍업 방송으로 확인하고 큐를 돌려준다. */
    private BlockingQueue<Map<String, Object>> subscribeReady(StompSession session,
            LectureRoom target) throws InterruptedException {
        String topic = "/topic/rooms/" + target.getId() + "/chat";
        var queue = client.subscribe(session, topic);
        assertThat(StompTestClient.awaitBroadcast(queue, () -> broker.convertAndSend(topic, Map.of("type", "WARMUP"))))
                .as("구독이 등록되어야 한다").isNotNull();
        return queue;
    }

    private LectureRoom liveRoom() {
        LectureRoom created = newRoom(host, "전송 테스트");
        jdbc.sql("UPDATE lecture_rooms SET status = 'LIVE' WHERE id = ?").param(created.getId()).update();
        return created;
    }

    private record Joined(String cookie, long participantId) { }

    private Joined join(String nickname) {
        return join(room, nickname);
    }

    private Joined join(LectureRoom target, String nickname) {
        var joined = participants.join(target.getJoinCode(), nickname, UUID.randomUUID().toString(), Map.of());
        return new Joined("PARTICIPANT_SESSION_" + target.getId() + "=" + joined.rawToken(),
                joined.response().participantId());
    }

    private String chatDestination() {
        return "/app/rooms/" + room.getId() + "/chat";
    }

    /** 저장은 서버에서 비동기로 일어나므로, 이 방의 첫 메시지가 저장될 때까지 기다렸다가 그 행을 돌려준다. */
    private Map<String, Object> awaitSavedMessage() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var rows = jdbc.sql("""
                            SELECT content, participant_id, host_user_id FROM chat_messages
                            WHERE lecture_room_id = ? ORDER BY id
                            """)
                    .param(room.getId()).query().listOfRows();
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("5초 안에 채팅 메시지가 저장되지 않았다");
    }

}
