package com.react.poll;

import com.react.poll.dto.CreatePollRequest;
import com.react.poll.dto.PollLifecycleEvent;
import com.react.poll.entity.PollCloseReason;
import com.react.poll.entity.PollType;
import com.react.poll.service.PollLifecycleBroadcaster;
import com.react.poll.service.PollLifecycleChangedEvent;
import com.react.poll.service.PollService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

/** 투표 생성·마감이 청중이 구독하는 /polls 토픽으로 나갈 알림을 남기는지, 방송 내용이 명세와 같은지 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
@RecordApplicationEvents
class PollLifecycleBroadcastTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired PollService service;
    @Autowired ApplicationEvents applicationEvents;
    private String hostEmail;
    private long room;

    @BeforeEach
    void prepareRoom() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).endsWith("_test");
        Flyway.configure().dataSource(dataSource).load().migrate();
        hostEmail = UUID.randomUUID() + "@example.com";
        long host = insert("INSERT INTO users(email,password_hash,name,created_at) VALUES (?, 'hash', '강연자', ?)",
                hostEmail, LocalDateTime.now(SEOUL));
        room = insert("INSERT INTO lecture_rooms(host_user_id,title,join_code,status,created_at) VALUES (?, 'test', ?, 'LIVE', ?)",
                host, UUID.randomUUID().toString(), LocalDateTime.now(SEOUL));
    }

    @Test
    void creatingAPollAnnouncesItToTheRoom() {
        var view = service.create(room, hostEmail, new CreatePollRequest(PollType.UNDERSTANDING, null, null, 60));

        var events = changes();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).roomId()).isEqualTo(room);
        var payload = events.get(0).payload();
        assertThat(payload.type()).isEqualTo("POLL_CREATED");
        var data = (PollLifecycleEvent.Created) payload.data();
        assertThat(data.pollId()).isEqualTo(view.pollId());
        assertThat(data.type()).isEqualTo(PollType.UNDERSTANDING);
        assertThat(data.options()).extracting(PollLifecycleEvent.Option::content).containsExactly("이해됨", "헷갈림", "모르겠음");
        assertThat(Instant.parse(data.closesAt())).isEqualTo(view.closesAt());
        assertThat(Instant.parse(data.serverTime())).isEqualTo(view.serverTime());
    }

    @Test
    void closingByHostAnnouncesTheCloseWithoutResultNumbers() {
        var view = service.create(room, hostEmail, new CreatePollRequest(PollType.UNDERSTANDING, null, null, 60));

        service.close(room, view.pollId(), hostEmail);

        var closed = (PollLifecycleEvent.Closed) lastChange().data();
        assertThat(lastChange().type()).isEqualTo("POLL_CLOSED");
        assertThat(closed.pollId()).isEqualTo(view.pollId());
        assertThat(closed.roomId()).isEqualTo(room);
        assertThat(closed.closeReason()).isEqualTo(PollCloseReason.HOST);
        assertThat(closed.closedAt()).isNotBlank();
    }

    @Test
    void endingTheRoomAnnouncesTheCloseOfOpenPolls() {
        var view = service.create(room, hostEmail, new CreatePollRequest(PollType.UNDERSTANDING, null, null, 60));

        service.closeOpenPollsOnRoomEnded(room);

        var closed = (PollLifecycleEvent.Closed) lastChange().data();
        assertThat(closed.pollId()).isEqualTo(view.pollId());
        assertThat(closed.closeReason()).isEqualTo(PollCloseReason.ROOM_ENDED);
    }

    @Test
    void aRejectedCloseAnnouncesNothing() {
        var view = service.create(room, hostEmail, new CreatePollRequest(PollType.UNDERSTANDING, null, null, 60));
        service.close(room, view.pollId(), hostEmail);
        long before = changes().size();

        try {
            service.close(room, view.pollId(), hostEmail);
        } catch (RuntimeException expected) {
            // 이미 마감된 투표
        }

        assertThat(changes()).hasSize((int) before);
    }

    @Test
    void broadcastsToThePollsTopicOfTheRoom() {
        var broker = mock(SimpMessagingTemplate.class);
        var event = new PollLifecycleChangedEvent(room,
                PollLifecycleEvent.closed(room, 5L, Instant.parse("2026-11-23T01:00:00Z"), PollCloseReason.HOST));

        new PollLifecycleBroadcaster(broker).onChanged(event);

        verify(broker).convertAndSend(eq("/topic/rooms/" + room + "/polls"), eq(event.payload()));
    }

    @Test
    void aBroadcastFailureDoesNotPropagate() {
        var broker = mock(SimpMessagingTemplate.class);
        doThrow(new IllegalStateException("broker down")).when(broker).convertAndSend(any(String.class), any(Object.class));

        assertThatCode(() -> new PollLifecycleBroadcaster(broker).onChanged(new PollLifecycleChangedEvent(room,
                PollLifecycleEvent.closed(room, 5L, Instant.now(), PollCloseReason.HOST)))).doesNotThrowAnyException();
    }

    private List<PollLifecycleChangedEvent> changes() {
        return applicationEvents.stream(PollLifecycleChangedEvent.class).filter(event -> event.roomId() == room).toList();
    }

    private PollLifecycleEvent lastChange() {
        var all = changes();
        return all.get(all.size() - 1).payload();
    }

    private long insert(String sql, Object... params) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql(sql).params(params).update(keys);
        return keys.getKey().longValue();
    }
}
