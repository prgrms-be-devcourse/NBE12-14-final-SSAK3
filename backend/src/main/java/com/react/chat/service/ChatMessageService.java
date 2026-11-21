package com.react.chat.service;

import com.react.chat.dto.ChatMessagePageView;
import com.react.chat.dto.ChatMessageView;
import com.react.chat.dto.ChatSenderView;
import com.react.chat.dto.SenderType;
import com.react.chat.entity.ChatMessage;
import com.react.chat.repository.ChatMessageQueryRepository;
import com.react.chat.repository.ChatMessageRepository;
import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
import com.react.global.websocket.StompActor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ChatMessageService {
    private static final int MAX_SIZE = 100;
    private static final int MAX_CONTENT_LENGTH = 1000;
    private final ChatMessageQueryRepository repository;
    private final ChatMessageRepository messageRepository;

    @Transactional
    public ChatMessageView saveFromParticipant(long roomId, long participantId, String content) {
        String text = normalize(content);
        String status = findRoomStatus(roomId);
        String nickname = repository.findParticipantNickname(roomId, participantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        requireLive(status);

        ChatMessage saved = messageRepository.save(ChatMessage.ofParticipant(roomId, participantId, text));
        return view(saved, new ChatSenderView(SenderType.PARTICIPANT, participantId, nickname));
    }

    @Transactional
    public ChatMessageView saveFromHost(long roomId, long hostUserId, String content) {
        String text = normalize(content);
        String status = findRoomStatus(roomId);
        String name = repository.findHostName(roomId, hostUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        requireLive(status);

        ChatMessage saved = messageRepository.save(ChatMessage.ofHost(roomId, hostUserId, text));
        return view(saved, new ChatSenderView(SenderType.HOST, hostUserId, name));
    }

    public ChatMessagePageView getMessages(long roomId, StompActor actor, Long cursor, int size) {
        if (size < 1 || size > MAX_SIZE || (cursor != null && cursor < 1)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (!repository.existsRoom(roomId)) {
            throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
        }

        // 한 건 더 가져와서 "다음 페이지가 있는지"만 판단한다.
        List<ChatMessageView> rows = repository.findPage(roomId, actor, cursor, size + 1);
        boolean hasNext = rows.size() > size;

        // 남는 한 건(가장 오래된 것)은 버리고, 화면 순서(오래된 → 최신)로 뒤집는다.
        List<ChatMessageView> page = new ArrayList<>(hasNext ? rows.subList(0, size) : rows);
        Collections.reverse(page);

        Long nextCursor = hasNext ? page.get(0).messageId() : null;
        return new ChatMessagePageView(page, nextCursor, hasNext);
    }

    // 앞뒤 공백을 제거하고, 비었거나 최대 길이를 넘으면 거절한다. 길이는 글자(코드포인트) 기준이다.
    private String normalize(String content) {
        String text = content == null ? "" : content.strip();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > MAX_CONTENT_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return text;
    }

    private String findRoomStatus(long roomId) {
        return repository.lockRoomStatusForSend(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
    }

    // 새 채팅은 LIVE 강연방에서만 받는다.
    private void requireLive(String status) {
        if ("ENDED".equals(status)) {
            throw new BusinessException(ErrorCode.ROOM_ENDED);
        }
        if (!"LIVE".equals(status)) {
            throw new BusinessException(ErrorCode.ROOM_NOT_LIVE);
        }
    }

    private ChatMessageView view(ChatMessage message, ChatSenderView sender) {
        return new ChatMessageView(message.getId(), sender, message.getContent(), message.getReceivedAt());
    }
}
