package com.react.global.websocket;

import com.react.auth.entity.User;
import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.poll.dto.CreatePollRequest;
import com.react.poll.dto.PollView;
import com.react.poll.entity.PollType;
import com.react.poll.service.PollResponseSavedEvent;
import com.react.poll.service.PollService;
import com.react.room.entity.LectureRoom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 청중이 응답하면 방 주인 강연자에게만 POLL_RESULTS_UPDATED 가 가고, 저장이 커밋된 뒤에만 나간다. */
class PollResultsBroadcastTest extends StompIntegrationTestSupport {
    private static final String EVENT = "POLL_RESULTS_UPDATED";

    @Autowired PollService pollService;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired PlatformTransactionManager transactionManager;

    private String hostEmail;
    private User host;
    private LectureRoom room;
    private PollView poll;
    private StompSession hostSession;
    private BlockingQueue<Map<String, Object>> results;

    @BeforeEach
    void prepare() throws Exception {
        hostEmail = "host-" + UUID.randomUUID() + "@example.com";
        host = newHost(hostEmail);
        room = liveRoom();
        poll = pollService.create(room.getId(), hostEmail, new CreatePollRequest(PollType.UNDERSTANDING, null, null, 60));
        hostSession = client.connect(StompTestClient.ALLOWED_ORIGIN, loginCookies(hostEmail));
        results = subscribeReady(hostSession, resultsTopic(room));
    }

    @Test
    void 청중이_응답하면_방_주인_강연자가_갱신된_결과를_받는다() throws Exception {
        long other = join(room);
        long responder = join(room);
        join(room);

        pollService.respond(room.getId(), poll.pollId(), responder, optionId(poll, 0));

        Map<String, Object> event = StompTestClient.nextOfType(results, EVENT, 5000);
        assertThat(event).as("결과 갱신 이벤트가 와야 한다").isNotNull();
        Map<String, Object> data = data(event);
        assertThat(((Number) data.get("pollId")).longValue()).isEqualTo(poll.pollId());
        assertThat(((Number) data.get("totalParticipants")).longValue()).isEqualTo(3);
        assertThat(((Number) data.get("respondedParticipants")).longValue()).isEqualTo(1);
        assertThat(((Number) data.get("unrespondedParticipants")).longValue()).isEqualTo(2);
        assertThat(data.get("counts")).isEqualTo(Map.of(
                String.valueOf(optionId(poll, 0)), 1, String.valueOf(optionId(poll, 1)), 0,
                String.valueOf(optionId(poll, 2)), 0));
        assertThat(other).isNotEqualTo(responder);
    }

    @Test
    void 선택을_바꾸면_바뀐_결과가_다시_온다() throws Exception {
        long participant = join(room);
        pollService.respond(room.getId(), poll.pollId(), participant, optionId(poll, 0));
        assertThat(StompTestClient.nextOfType(results, EVENT, 5000)).isNotNull();

        pollService.respond(room.getId(), poll.pollId(), participant, optionId(poll, 1));

        Map<String, Object> data = data(StompTestClient.nextOfType(results, EVENT, 5000));
        assertThat(((Number) data.get("respondedParticipants")).longValue()).isEqualTo(1);
        assertThat(data.get("counts")).isEqualTo(Map.of(
                String.valueOf(optionId(poll, 0)), 0, String.valueOf(optionId(poll, 1)), 1,
                String.valueOf(optionId(poll, 2)), 0));
    }

    @Test
    void 같은_선택을_다시_보내면_바뀐_게_없으므로_보내지_않는다() throws Exception {
        long participant = join(room);
        pollService.respond(room.getId(), poll.pollId(), participant, optionId(poll, 0));
        assertThat(StompTestClient.nextOfType(results, EVENT, 5000)).isNotNull();

        pollService.respond(room.getId(), poll.pollId(), participant, optionId(poll, 0));

        assertThat(StompTestClient.nextOfType(results, EVENT, 700)).isNull();
    }

    @Test
    void 거절된_응답은_방송하지_않는다() throws Exception {
        long participant = join(room);
        pollService.close(room.getId(), poll.pollId(), hostEmail);

        assertThatThrownBy(() -> pollService.respond(room.getId(), poll.pollId(), participant, optionId(poll, 0)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.POLL_CLOSED));

        assertThat(StompTestClient.nextOfType(results, EVENT, 700)).isNull();
    }

    @Test
    void 롤백되면_방송하지_않고_커밋되면_방송한다() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            publisher.publishEvent(new PollResponseSavedEvent(room.getId(), poll.pollId()));
            status.setRollbackOnly();
        });
        assertThat(StompTestClient.nextOfType(results, EVENT, 700)).as("롤백된 트랜잭션은 방송하지 않는다").isNull();

        tx.executeWithoutResult(status ->
                publisher.publishEvent(new PollResponseSavedEvent(room.getId(), poll.pollId())));
        assertThat(StompTestClient.nextOfType(results, EVENT, 5000)).as("커밋되면 방송한다").isNotNull();
    }

    @Test
    void 다른_방의_응답은_이_방_결과_채널에_오지_않는다() throws Exception {
        LectureRoom otherRoom = liveRoom();
        PollView otherPoll = pollService.create(otherRoom.getId(), hostEmail,
                new CreatePollRequest(PollType.UNDERSTANDING, null, null, 60));

        pollService.respond(otherRoom.getId(), otherPoll.pollId(), join(otherRoom), optionId(otherPoll, 0));

        assertThat(StompTestClient.nextOfType(results, EVENT, 700)).isNull();
    }

    /** 구독한 뒤, 서버가 그 구독을 등록해 방송이 닿는 것을 워밍업 방송으로 확인한다. */
    private BlockingQueue<Map<String, Object>> subscribeReady(StompSession session, String topic)
            throws InterruptedException {
        var queue = client.subscribe(session, topic);
        assertThat(StompTestClient.awaitBroadcast(queue, () -> broker.convertAndSend(topic, Map.of("type", "WARMUP"))))
                .as("구독이 등록되어야 한다").isNotNull();
        return queue;
    }

    private LectureRoom liveRoom() {
        LectureRoom created = newRoom(host, "결과 방송 테스트");
        jdbc.sql("UPDATE lecture_rooms SET status = 'LIVE' WHERE id = ?").param(created.getId()).update();
        return created;
    }

    private long join(LectureRoom target) {
        return participants.join(target.getJoinCode(), "청중", UUID.randomUUID().toString(), Map.of())
                .response().participantId();
    }

    private static String resultsTopic(LectureRoom target) {
        return "/topic/rooms/" + target.getId() + "/polls/results";
    }

    private static long optionId(PollView target, int index) {
        return target.options().get(index).optionId();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> event) {
        return (Map<String, Object>) event.get("data");
    }
}
