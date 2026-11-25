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

채팅 화면은 내역 조회(HTTP)와 방 채팅 구독·전송(STOMP)에 `X-Chat-Role: HOST` 또는 `PARTICIPANT`를 보낸다. STOMP `CONNECT`에도 선택한 역할과 `X-CSRF-TOKEN`을 전달한다. 같은 브라우저에 두 세션이 있어도 강연자 화면은 방 소유자를, 청중 화면은 해당 방의 참여자를 기준으로 처리한다. 서버는 선택된 역할의 세션과 방 권한을 검증한다.

연결 전 `GET /api/v1/auth/csrf`로 CSRF 토큰을 발급받으며, 최초 연결 및 재연결 시 현재 세션의 토큰을 다시 확인한다. CSRF 요청이 일시적으로 실패(네트워크 오류·시간 초과·429·5xx)하면 약 3초 간격으로 재시도한다. 인증 실패(401·403)는 자동 재시도하지 않는다. 연결 복구 후에는 REST 채팅 내역을 다시 조회하여 누락된 메시지를 확인한다.

화면은 메시지를 보낼 때마다 STOMP 헤더 `X-Chat-Attempt`에 새 UUID를 담는다. 서버는 저장 후 해당 `CHAT_CREATED` 프레임의 같은 헤더에 값을 돌려준다. 발신자 화면은 이를 현재 전송과 대조해 다른 탭에서 같은 문구를 보내도 구분한다.

서버는 이 값을 `chat_messages.client_message_id`에 저장하고, 같은 강연방에서 같은 작성자가 같은 값으로 보낸 요청은 한 번만 저장한다(CHAT-06). 전송 결과를 확인하지 못해 ‘다시 보내기’를 누를 때 화면은 처음 전송과 같은 UUID를 쓴다. 이미 저장된 요청이면 새로 저장하지 않고, 기존 메시지를 같은 헤더의 `CHAT_CREATED`로 다시 전파한다. 헤더가 없거나 UUID 형식이 아니면 중복 검사 없이 새 메시지로 저장한다. 강연이 종료된 뒤에는 이미 저장된 메시지의 재전송도 `ROOM_ENDED`로 거절한다.

규칙:

- 강연자와 청중은 같은 방의 대화를 같은 순서로 읽는다. 양쪽 모두 방이 LIVE일 때만 새 메시지를 보낼 수 있다. 두 화면 모두 `sender.type`이 `HOST`인 메시지의 작성자 이름 옆에 ‘강연자’를 표시한다.
- 공백·빈 문자열과 1,000자를 초과하는 메시지는 거절한다.
- 작성자는 서버가 식별하며 `participant_id`, `host_user_id` 중 하나만 저장한다.
- 저장이 끝난 메시지를 강연방 참가자에게 전파한다.
- 강연자와 청중 모두 전송 직후 연결이 끊겨 결과를 확인할 수 없으면 작성한 문구를 보관하고 “전송 여부를 확인하지 못했어요. 채팅 내역을 확인해 주세요”라고 안내한다. 재연결되면 해당 방의 채팅 내역을 먼저 갱신한다. 자동 재전송은 하지 않으며, 방이 LIVE인 동안 사용자가 내역을 확인하고 ‘다시 보내기’를 선택했을 때만 재전송한다.
- 다른 강연방 채널에 전송하거나 구독할 수 없다.
- 채팅 저장은 AI 분석 완료 여부와 독립적이다. AI 청중 의견 요약에는 청중 작성 메시지만 포함한다.

오류 이벤트는 발신자 전용 채널 `/user/queue/errors` 로 전달한다. 유효한 `X-Chat-Attempt`가 있으면 오류 본문의 `attempt`에도 같은 값을 담는다. 코드는 REST 와 같다: `INVALID_INPUT`(빈 문자열·1,000자 초과·본문 형식 오류), `UNAUTHENTICATED`(이 방의 쿠키·로그인 없음), `FORBIDDEN`(다른 방 소속), `ROOM_NOT_LIVE`, `ROOM_ENDED`. 예상하지 못한 오류는 내부 내용 없이 `INTERNAL_ERROR` 만 알린다.

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
강연자는 강연 시작 시점부터, 청중은 해당 방에 최초로 입장한 시점부터의 대화를 본다. 입장 시각과 메시지 수신 시각은 초 단위로 비교하며, 입장한 초에 수신한 메시지도 포함한다(`received_at >= joined_at`). 같은 참여 세션에서 새로고침하거나 다시 연결해도 최초 입장 시점을 유지한다.
두 화면 모두 채팅 목록 맨 아래를 보고 있으면 새 메시지가 도착할 때 최신 메시지로 자동 스크롤한다. 이전 대화를 읽고 있으면 현재 위치를 유지하고 ‘새 메시지 N개 ↓’ 버튼에 새로 도착한 개수를 표시한다. 버튼을 누르면 최신 메시지로 이동한다.
강연이 끝나면 강연자의 방 상세 화면에는 입력창을 숨기고 기존 채팅을 읽기 전용으로 남긴다. 청중은 ‘강연이 종료되었습니다’라는 안내가 있는 참여 종료 화면으로 이동한다.
정렬·페이지: 각 페이지는 `cursor` 보다 이전 메시지 중 최신 `size`개를 가져오되, `messages` 배열은 오래된 순으로 정렬한다(화면은 최신이 아래). `nextCursor` 는 배열의 첫 번째(가장 오래된) 메시지 ID 이며, 더 오래된 메시지가 없으면(`hasNext` 가 `false`) `null` 이다. 다음 페이지는 이 값을 `cursor` 로 요청한다. `size` 는 1~100, `cursor` 는 1 이상이어야 한다.
오류: `400 INVALID_INPUT`, `401 UNAUTHENTICATED`, `403 FORBIDDEN`, `404 ROOM_NOT_FOUND`
