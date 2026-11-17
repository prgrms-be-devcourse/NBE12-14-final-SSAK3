package com.react.global.websocket;

import org.springframework.context.annotation.Profile;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

@Controller
@Profile("local")
public class DevStompController {

    @MessageMapping("/_dev/ping")
    @SendTo("/topic/_dev/pong")
    public String ping(String message) {
        return "pong";
    }
}
