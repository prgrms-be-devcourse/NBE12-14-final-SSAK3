package com.react.chat.service;

import com.react.chat.dto.ChatBatchEvent;
import com.react.chat.dto.ChatSenderView;
import com.react.global.monitoring.ChatMessageMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ChatBroadcastBatcherTest {
    private SimpMessagingTemplate broker;
    private ChatMessageMetrics metrics;
    private ChatBroadcastBatcher batcher;

    @BeforeEach
    void setUp() {
        broker = mock(SimpMessagingTemplate.class);
        metrics = mock(ChatMessageMetrics.class);
        batcher = new ChatBroadcastBatcher(broker, metrics);
    }

    @Test
    void 모은_메시지는_방마다_한_프레임으로_보낸다() {
        batcher.enqueue(1, message(1, 12));
        batcher.enqueue(1, message(1, 11));
        batcher.enqueue(2, message(2, 21));

        batcher.flush();

        ArgumentCaptor<ChatBatchEvent> room1 = ArgumentCaptor.forClass(ChatBatchEvent.class);
        verify(broker).convertAndSend(eq("/topic/rooms/1/chat"), room1.capture());
        // 저장 순서와 상관없이 messageId 오름차순이다.
        assertThat(room1.getValue().data().messages()).extracting(ChatBatchEvent.Message::messageId)
                .containsExactly(11L, 12L);
        verify(broker).convertAndSend(eq("/topic/rooms/2/chat"), any(ChatBatchEvent.class));
    }

    @Test
    void 한_프레임의_최대_개수를_넘으면_나누어_보낸다() {
        IntStream.rangeClosed(1, ChatBroadcastBatcher.MAX_BATCH_SIZE * 2 + 5)
                .forEach(id -> batcher.enqueue(1, message(1, id)));

        batcher.flush();

        ArgumentCaptor<ChatBatchEvent> sent = ArgumentCaptor.forClass(ChatBatchEvent.class);
        verify(broker, org.mockito.Mockito.times(3)).convertAndSend(eq("/topic/rooms/1/chat"), sent.capture());
        assertThat(sent.getAllValues()).extracting(event -> event.data().messages().size())
                .containsExactly(ChatBroadcastBatcher.MAX_BATCH_SIZE, ChatBroadcastBatcher.MAX_BATCH_SIZE, 5);
    }

    @Test
    void 보낸_메시지는_다음_flush_에서_다시_보내지_않는다() {
        batcher.enqueue(1, message(1, 1));
        batcher.flush();
        batcher.flush();

        verify(broker).convertAndSend(eq("/topic/rooms/1/chat"), any(ChatBatchEvent.class));
    }

    @Test
    void 모은_메시지가_없으면_보내지_않는다() {
        batcher.flush();

        verify(broker, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void 전송이_실패하면_실패를_집계하고_다른_방은_계속_보낸다() {
        doThrow(new IllegalStateException("closed")).when(broker)
                .convertAndSend(eq("/topic/rooms/1/chat"), any(ChatBatchEvent.class));
        batcher.enqueue(1, message(1, 1));
        batcher.enqueue(2, message(2, 2));

        batcher.flush();

        verify(metrics).recordBroadcastFailure();
        verify(broker).convertAndSend(eq("/topic/rooms/2/chat"), any(ChatBatchEvent.class));
    }

    private static ChatBatchEvent.Message message(long roomId, long messageId) {
        return new ChatBatchEvent.Message(messageId, roomId, new ChatSenderView(com.react.chat.dto.SenderType.PARTICIPANT, 1L, "청중"),
                "내용", "2026-11-26T00:00:00Z", null);
    }
}
