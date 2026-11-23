package com.react.global.websocket;

import com.react.chat.dto.ChatErrorEvent;
import com.react.global.exception.BusinessException;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 클라이언트의 STOMP SEND 목적지와 방별 전송 권한을 검사한다.
 *
 * 인증되지 않은 목적지는 메시지 처리 단계로 전달하지 않는다.
 * 채팅의 방별 권한 오류는 기존 규약대로 개인 오류 큐에 전달한다.
 */
@Component
public class StompSendAuthorizationInterceptor implements ChannelInterceptor {

    private static final Pattern CHAT_DESTINATION =
            Pattern.compile("^/app/rooms/([1-9][0-9]{0,17})/chat$");

    private static final String DEV_PING = "/app/_dev/ping";
    private static final String ATTEMPT_HEADER = "X-Chat-Attempt";

    private final StompActorResolver actors;
    private final SimpMessagingTemplate broker;
    private final Environment environment;

    public StompSendAuthorizationInterceptor(
            StompActorResolver actors,
            @Lazy SimpMessagingTemplate broker,
            Environment environment
    ) {
        this.actors = actors;
        this.broker = broker;
        this.environment = environment;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || accessor.getCommand() != StompCommand.SEND) {
            return message;
        }

        String destination = accessor.getDestination();

        if (destination == null) {
            throw new AccessDeniedException("전송할 수 없는 주소입니다.");
        }

        // 개발용 컨트롤러는 local 프로필에서만 활성화된다.
        if (DEV_PING.equals(destination)
                && environment.acceptsProfiles(Profiles.of("local"))) {
            return message;
        }

        Matcher chat = CHAT_DESTINATION.matcher(destination);

        if (!chat.matches()) {
            throw new AccessDeniedException("허용되지 않은 STOMP SEND 주소입니다.");
        }

        Principal principal = accessor.getUser();
        String sessionId = accessor.getSessionId();

        if (principal == null || sessionId == null) {
            throw new AccessDeniedException("STOMP 연결 인증이 필요합니다.");
        }

        long roomId = Long.parseLong(chat.group(1));

        try {
            // 구독 검사와 동일한 방별 권한 판별기를 사용한다.
            // HOST는 방 소유권, 청중은 해당 방 쿠키를 검증한다.
            // X-Chat-Role을 적용해 동일 브라우저의 역할 선택을 유지한다.
            actors.resolveChat(roomId, accessor);
            return message;

        } catch (BusinessException exception) {
            // 기존 채팅 명세에서는 방별 인증 오류를
            // 연결 종료가 아닌 개인 오류 메시지로 전달한다.
            sendPrivateError(
                    principal,
                    sessionId,
                    exception,
                    attempt(accessor)
            );

            // 권한이 없는 SEND는 컨트롤러에 도달하지 않는다.
            return null;
        }
    }

    private void sendPrivateError(
            Principal principal,
            String sessionId,
            BusinessException exception,
            String attempt
    ) {
        ChatErrorEvent payload =
                ChatErrorEvent.of(exception.getErrorCode(), attempt);

        // 같은 사용자의 여러 소켓 중
        // 오류를 발생시킨 소켓으로만 전달한다.
        SimpMessageHeaderAccessor headers =
                SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);

        headers.setSessionId(sessionId);
        headers.setLeaveMutable(true);

        broker.convertAndSendToUser(
                principal.getName(),
                "/queue/errors",
                payload,
                headers.getMessageHeaders()
        );
    }

    private static String attempt(StompHeaderAccessor accessor) {
        String value = accessor.getFirstNativeHeader(ATTEMPT_HEADER);

        if (value == null || value.length() != 36) {
            return null;
        }

        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value)
                    ? value
                    : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}