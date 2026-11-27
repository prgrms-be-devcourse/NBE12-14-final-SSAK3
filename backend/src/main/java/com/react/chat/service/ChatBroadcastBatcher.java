package com.react.chat.service;

import com.react.chat.dto.ChatBatchEvent;
import com.react.global.monitoring.ChatMessageMetrics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 저장된 채팅을 강연방별로 잠깐 모았다가 한 프레임에 묶어 보낸다.
 * 채팅마다 바로 보내면 청중 수 N 에 대해 프레임이 N × N 개로 늘어나므로, 청중 한 명이 받는 프레임 수를
 * 초당 (1000 / FLUSH_INTERVAL_MS) 개 이하로 묶는다. 채팅 한 건이 최대 FLUSH_INTERVAL_MS 만큼 늦게 전달된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatBroadcastBatcher {
    static final long FLUSH_INTERVAL_MS = 100;
    /** 한 프레임에 담는 최대 메시지 수. 프레임 크기가 메시지 크기 한도(64KB)를 넘지 않게 한다. */
    static final int MAX_BATCH_SIZE = 50;

    private final SimpMessagingTemplate broker;
    private final ChatMessageMetrics metrics;
    private final Map<Long, Queue<ChatBatchEvent.Message>> pending = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "chat-broadcast-batcher");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::flush, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdown();
        flush();
    }

    /** 저장이 커밋된 뒤에 부른다. 실제 전송은 다음 flush 에서 한다. */
    public void enqueue(long roomId, ChatBatchEvent.Message message) {
        // 큐 조회와 메시지 추가를 원자적으로 처리해 큐 제거와의 충돌을 방지한다.
        pending.compute(roomId, (id, queue) -> {
            if (queue == null) {
                queue = new ConcurrentLinkedQueue<>();
            }
            queue.add(message);
            return queue;
        });
    }

    void flush() {
        for (Long roomId : List.copyOf(pending.keySet())) {
            Queue<ChatBatchEvent.Message> queue = pending.get(roomId);
            if (queue == null) {
                continue;
            }
            List<ChatBatchEvent.Message> drained = new ArrayList<>();
            ChatBatchEvent.Message next;
            while ((next = queue.poll()) != null) {
                drained.add(next);
            }
            if (drained.isEmpty()) {
                // 큐 제거 직전에 다시 확인하고, 그 사이 메시지가 추가됐다면 큐를 유지한다.
                pending.computeIfPresent(roomId, (id, currentQueue) -> {
                    if (currentQueue == queue && currentQueue.isEmpty()) {
                        return null;
                    }
                    return currentQueue;
                });
                continue;
            }
            drained.sort(Comparator.comparingLong(ChatBatchEvent.Message::messageId));
            for (int from = 0; from < drained.size(); from += MAX_BATCH_SIZE) {
                send(roomId, drained.subList(from, Math.min(from + MAX_BATCH_SIZE, drained.size())));
            }
        }
    }

    private void send(long roomId, List<ChatBatchEvent.Message> messages) {
        // 이미 커밋된 뒤이므로 전송이 실패해도 저장된 채팅은 유지하고, 내역 조회로 복구할 수 있다.
        try {
            broker.convertAndSend("/topic/rooms/" + roomId + "/chat", ChatBatchEvent.of(roomId, List.copyOf(messages)));
        } catch (RuntimeException e) {
            metrics.recordBroadcastFailure();
            log.warn("채팅 전송 실패. roomId={}, messages={}", roomId, messages.size(), e);
        }
    }
}