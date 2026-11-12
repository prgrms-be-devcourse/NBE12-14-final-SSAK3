package com.csmatch.global.websocket;

/** 경기 모듈 연동용 이벤트. 교체된 옛 연결의 종료는 발행하지 않는다. */
public record WebSocketDisconnected(long userId, String sessionId, String reason) {
}
