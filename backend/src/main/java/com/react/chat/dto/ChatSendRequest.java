package com.react.chat.dto;

/** STOMP 채팅 전송 본문. 작성자는 본문이 아니라 서버가 쿠키·로그인으로 확인하므로 content 만 받는다. */
public record ChatSendRequest(String content) { }
