package com.csmatch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import com.csmatch.global.websocket.MatchWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class CsMatchApplicationTests {
    @Autowired
    private MatchWebSocketHandler webSocketHandler;

    @Test
    void contextLoads() {
    }

    @Test
    void realJwtAdapterRejectsMalformedToken() throws Exception {
        var session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("invalid-jwt");
        when(session.isOpen()).thenReturn(true);
        webSocketHandler.afterConnectionEstablished(session);
        webSocketHandler.handleMessage(session, new TextMessage(
                "{\"type\":\"AUTH\",\"payload\":{\"accessToken\":\"unverified\"}}"));
        verify(session).close(new CloseStatus(1008, "AUTH_FAILED"));
    }
}
