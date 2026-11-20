package com.react.global.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 구독(SUBSCRIBE) 검문소. 기본은 전부 거절이고, 허용하는 주소만 연다.
 * 방별 채널은 그 방의 강연자·청중만 구독할 수 있고, 거절되면 서버가 STOMP ERROR 를 보내고 연결을 닫는다.
 */
@Component
@RequiredArgsConstructor
public class StompSubscribeInterceptor implements ChannelInterceptor {
    /** 방별 채널. 방 번호는 숫자만 받고, 뒤의 종류는 허용 목록에 있는 것만 받는다. */
    private static final Pattern ROOM_TOPIC = Pattern.compile("^/topic/rooms/([1-9][0-9]{0,17})/(chat|status)$");
    /** 스프링이 로그인한 본인의 것만 전달해 주는 개인 우편함. */
    private static final String USER_QUEUE_PREFIX = "/user/queue/";
    /** 로컬 개발용 ping 확인 주소. 컨트롤러가 local 프로필에만 있다. */
    private static final String DEV_TOPIC_PREFIX = "/topic/_dev/";

    private final StompActorResolver actors;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() != StompCommand.SUBSCRIBE) {
            return message;
        }

        String destination = accessor.getDestination();
        if (destination == null) {
            throw new AccessDeniedException("구독할 수 없는 주소입니다.");
        }

        Matcher room = ROOM_TOPIC.matcher(destination);
        if (room.matches()) {
            // 이 방의 강연자나 청중이 아니면 BusinessException(401·403) 으로 거절된다.
            actors.resolve(Long.parseLong(room.group(1)), accessor.getUser(), accessor.getSessionAttributes());
            return message;
        }
        if (destination.startsWith(USER_QUEUE_PREFIX) || destination.startsWith(DEV_TOPIC_PREFIX)) {
            return message;
        }
        throw new AccessDeniedException("구독할 수 없는 주소입니다.");
    }
}
