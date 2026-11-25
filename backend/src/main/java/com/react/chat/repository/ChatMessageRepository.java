package com.react.chat.repository;

import com.react.chat.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    /** 같은 강연방에서 그 청중이 같은 전송 식별자로 이미 저장한 메시지. */
    Optional<ChatMessage> findByLectureRoomIdAndParticipantIdAndClientMessageId(
            Long lectureRoomId, Long participantId, String clientMessageId);

    /** 같은 강연방에서 그 강연자가 같은 전송 식별자로 이미 저장한 메시지. */
    Optional<ChatMessage> findByLectureRoomIdAndHostUserIdAndClientMessageId(
            Long lectureRoomId, Long hostUserId, String clientMessageId);
}
