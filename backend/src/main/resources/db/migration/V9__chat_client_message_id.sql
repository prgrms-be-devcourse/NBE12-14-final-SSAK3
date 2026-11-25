-- 재전송으로 같은 메시지가 두 번 저장되지 않도록 화면이 만든 전송 식별자(UUID)를 저장한다. 없는 요청은 NULL 이다.
ALTER TABLE chat_messages ADD COLUMN client_message_id CHAR(36) NULL;

-- 작성자 컬럼 중 하나는 항상 NULL 이라 한 인덱스로는 막을 수 없다. 작성자별로 따로 건다. NULL 식별자는 제한하지 않는다.
CREATE UNIQUE INDEX uk_chat_messages_participant_client
    ON chat_messages (lecture_room_id, participant_id, client_message_id);
CREATE UNIQUE INDEX uk_chat_messages_host_client
    ON chat_messages (lecture_room_id, host_user_id, client_message_id);
