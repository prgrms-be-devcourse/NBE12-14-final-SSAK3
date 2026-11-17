package com.react.chat.service;

import com.react.chat.dto.ChatMessagePageView;
import com.react.chat.dto.ChatMessageView;
import com.react.chat.repository.ChatMessageQueryRepository;
import com.react.global.exception.BusinessException;
import com.react.global.exception.ErrorCode;
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
    private final ChatMessageQueryRepository repository;

    public ChatMessagePageView getMessages(long roomId, Long cursor, int size) {
        if (size < 1 || size > MAX_SIZE || (cursor != null && cursor < 1)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (!repository.existsRoom(roomId)) {
            throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
        }

        // 한 건 더 가져와서 "다음 페이지가 있는지"만 판단한다.
        List<ChatMessageView> rows = repository.findPage(roomId, cursor, size + 1);
        boolean hasNext = rows.size() > size;

        // 남는 한 건(가장 오래된 것)은 버리고, 화면 순서(오래된 → 최신)로 뒤집는다.
        List<ChatMessageView> page = new ArrayList<>(hasNext ? rows.subList(0, size) : rows);
        Collections.reverse(page);

        Long nextCursor = hasNext ? page.get(0).messageId() : null;
        return new ChatMessagePageView(page, nextCursor, hasNext);
    }
}
