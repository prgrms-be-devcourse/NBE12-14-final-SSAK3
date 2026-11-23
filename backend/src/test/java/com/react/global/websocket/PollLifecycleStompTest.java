package com.react.global.websocket;

import com.react.auth.entity.User;
import com.react.poll.dto.CreatePollRequest;
import com.react.poll.dto.PollView;
import com.react.poll.entity.PollType;
import com.react.poll.service.PollExpiryProcessor;
import com.react.poll.service.PollService;
import com.react.room.entity.LectureRoom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.stomp.StompSession;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;

/** 강연자가 이해도 체크를 열고 닫으면 같은 방 청중의 /polls 구독에 바로 POLL_CREATED·POLL_CLOSED 가 온다. */
class PollLifecycleStompTest extends StompIntegrationTestSupport {
    @Autowired PollService pollService;
    @Autowired PollExpiryProcessor expiry;

    private String hostEmail;
    private LectureRoom room;
    private BlockingQueue<Map<String, Object>> audience;

    @BeforeEach
    void prepare() throws Exception {
        hostEmail = "host-" + UUID.randomUUID() + "@example.com";
        User host = newHost(hostEmail);
        room = newRoom(host, "투표 생성·마감 방송 테스트");
        jdbc.sql("UPDATE lecture_rooms SET status = 'LIVE' WHERE id = ?").param(room.getId()).update();
        StompSession session = client.connect(StompTestClient.ALLOWED_ORIGIN, participantCookie(room));
        audience = client.subscribe(session, pollsTopic(room));
        assertThat(StompTestClient.awaitBroadcast(audience,
                () -> broker.convertAndSend(pollsTopic(room), Map.of("type", "WARMUP"))))
                .as("구독이 등록되어야 한다").isNotNull();
    }

    @Test
    void 청중은_이해도_체크가_열리면_받는다() throws Exception {
        PollView poll = pollService.create(room.getId(), hostEmail,
                new CreatePollRequest(PollType.UNDERSTANDING, null, null, 60));

        Map<String, Object> event = StompTestClient.nextOfType(audience, "POLL_CREATED", 5000);

        assertThat(event).isNotNull();
        Map<String, Object> data = data(event);
        assertThat(((Number) data.get("pollId")).longValue()).isEqualTo(poll.pollId());
        assertThat(((Number) data.get("roomId")).longValue()).isEqualTo(room.getId());
        assertThat(data.get("type")).isEqualTo("UNDERSTANDING");
        assertThat(data.get("status")).isEqualTo("OPEN");
        assertThat(data.get("closesAt")).isEqualTo(poll.closesAt().toString());
        assertThat(data.get("serverTime")).isEqualTo(poll.serverTime().toString());
        assertThat((List<?>) data.get("options")).hasSize(3);
    }

    @Test
    void 청중은_강연자가_마감하면_바로_받고_결과_수치는_없다() throws Exception {
        PollView poll = pollService.create(room.getId(), hostEmail,
                new CreatePollRequest(PollType.UNDERSTANDING, null, null, 60));
        assertThat(StompTestClient.nextOfType(audience, "POLL_CREATED", 5000)).isNotNull();

        pollService.close(room.getId(), poll.pollId(), hostEmail);

        Map<String, Object> event = StompTestClient.nextOfType(audience, "POLL_CLOSED", 5000);
        assertThat(event).as("마감 이벤트가 와야 한다").isNotNull();
        Map<String, Object> data = data(event);
        assertThat(((Number) data.get("pollId")).longValue()).isEqualTo(poll.pollId());
        assertThat(data.get("closeReason")).isEqualTo("HOST");
        assertThat(data).containsOnlyKeys("pollId", "roomId", "closedAt", "closeReason");
    }

    @Test
    void 청중은_제한_시간이_지나_서버가_마감하면_받는다() throws Exception {
        PollView poll = pollService.create(room.getId(), hostEmail,
                new CreatePollRequest(PollType.UNDERSTANDING, null, null, 60));
        assertThat(StompTestClient.nextOfType(audience, "POLL_CREATED", 5000)).isNotNull();
        jdbc.sql("UPDATE polls SET created_at = DATE_SUB(created_at, INTERVAL 5 MINUTE), closes_at = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE id = ?")
                .param(poll.pollId()).update();

        expiry.closeExpiredPolls();

        Map<String, Object> event = StompTestClient.nextOfType(audience, "POLL_CLOSED", 5000);
        assertThat(event).as("만료 마감 이벤트가 와야 한다").isNotNull();
        assertThat(((Number) data(event).get("pollId")).longValue()).isEqualTo(poll.pollId());
        assertThat(data(event).get("closeReason")).isEqualTo("TIMEOUT");
    }

    @Test
    void 다른_방의_마감은_이_방_구독에_오지_않는다() throws Exception {
        LectureRoom other = newRoom(newHost("other-" + UUID.randomUUID() + "@example.com"), "다른 방");
        jdbc.sql("UPDATE lecture_rooms SET status = 'LIVE' WHERE id = ?").param(other.getId()).update();
        String otherHost = jdbc.sql("SELECT u.email FROM users u JOIN lecture_rooms r ON r.host_user_id = u.id WHERE r.id = ?")
                .param(other.getId()).query(String.class).single();

        PollView poll = pollService.create(other.getId(), otherHost,
                new CreatePollRequest(PollType.UNDERSTANDING, null, null, 60));
        pollService.close(other.getId(), poll.pollId(), otherHost);

        assertThat(StompTestClient.nextOfType(audience, "POLL_CLOSED", 700)).isNull();
    }

    private String participantCookie(LectureRoom target) {
        var joined = participants.join(target.getJoinCode(), "청중", UUID.randomUUID().toString(), Map.of());
        return "PARTICIPANT_SESSION_" + target.getId() + "=" + joined.rawToken();
    }

    private static String pollsTopic(LectureRoom target) {
        return "/topic/rooms/" + target.getId() + "/polls";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> event) {
        return (Map<String, Object>) event.get("data");
    }
}
