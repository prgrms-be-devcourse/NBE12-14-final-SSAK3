# CHAT — 실시간 채팅


### [CHAT-01, 02, 04, 05] 채팅 메시지 전송

통신: WebSocket / STOMP — 인증: HOST 또는 PARTICIPANT

**SEND** `/app/rooms/{roomId}/chat`

```json
{ "content": "Redis와 MySQL의 차이가 궁금합니다." }
```

**SUBSCRIBE** `/topic/rooms/{roomId}/chat`

```json
{
  "type": "CHAT_CREATED",
  "data": {
    "messageId": 1001,
    "roomId": 10,
    "sender": { "type": "PARTICIPANT", "id": 101, "name": "청중A" },
    "content": "Redis와 MySQL의 차이가 궁금합니다.",
    "receivedAt": "2026-11-17T01:02:00Z"
  }
}
```

강연자 발신자 예시: `{ "type": "HOST", "id": 1, "name": "김강연" }`

규칙:

- 강연자와 청중 모두 채팅 가능, LIVE 에서만 신규 메시지 전송 가능.
- 공백·빈 문자열 거부, 최대 길이 1,000자를 제안한다.
- 작성자는 서버가 식별하며 `participant_id`, `host_user_id` 중 하나만 저장한다.
- 저장이 끝난 메시지를 강연방 참가자에게 전파한다.
- 다른 강연방 채널에 전송하거나 구독할 수 없다.
- 채팅 저장은 AI 분석 완료 여부와 독립적이다. AI 청중 의견 요약에는 청중 작성 메시지만 포함한다.

오류 이벤트는 발신자 전용 채널 `/user/queue/errors` 로 전달한다. 코드는 REST 와 같다: `INVALID_INPUT`(빈 문자열·1,000자 초과·본문 형식 오류), `UNAUTHENTICATED`(이 방의 쿠키·로그인 없음), `FORBIDDEN`(다른 방 소속), `ROOM_NOT_LIVE`, `ROOM_ENDED`. 예상하지 못한 오류는 내부 내용 없이 `INTERNAL_ERROR` 만 알린다.

```json
{ "type": "ERROR", "code": "ROOM_NOT_LIVE", "message": "진행 중인 강연이 아닙니다." }
```

엔터티: `chat_messages`

### [CHAT-03] 강연방별 채팅 내역 조회

**GET** `/api/v1/rooms/{roomId}/messages` — HOST 또는 해당 PARTICIPANT

| 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- |
| cursor | Long | N | 없음 | 해당 메시지 ID 보다 이전 메시지 조회 |
| size | Integer | N | 30 | 조회 개수, 최대 100 |

예: `GET /api/v1/rooms/10/messages?cursor=1005&size=30`

Response — 200 OK

```json
{
  "data": {
    "messages": [
      {
        "messageId": 1001,
        "sender": { "type": "PARTICIPANT", "id": 101, "name": "청중A" },
        "content": "Redis와 MySQL의 차이가 궁금합니다.",
        "receivedAt": "2026-11-17T01:02:00Z"
      },
      {
        "messageId": 1002,
        "sender": { "type": "HOST", "id": 1, "name": "김강연" },
        "content": "예시를 들어 설명할게요.",
        "receivedAt": "2026-11-17T01:02:10Z"
      }
    ],
    "nextCursor": 1001,
    "hasNext": true
  }
}
```

규칙: 해당 강연방 메시지만 조회, 커서 기반 페이지 조회, 종료된 강연도 기존 메시지 조회 가능, AI 전처리 제외 여부와 무관하게 원문 조회 가능.
정렬·페이지: 각 페이지는 `cursor` 보다 이전 메시지 중 최신 `size`개를 가져오되, `messages` 배열은 오래된 순으로 정렬한다(화면은 최신이 아래). `nextCursor` 는 배열의 첫 번째(가장 오래된) 메시지 ID 이며, 더 오래된 메시지가 없으면(`hasNext` 가 `false`) `null` 이다. 다음 페이지는 이 값을 `cursor` 로 요청한다. `size` 는 1~100, `cursor` 는 1 이상이어야 한다.
오류: `400 INVALID_INPUT`, `401 UNAUTHENTICATED`, `403 FORBIDDEN`, `404 ROOM_NOT_FOUND`

