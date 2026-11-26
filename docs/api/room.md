# ROOM — 강연방 관리

### [ROOM-08] 강연자 홈의 진행 중·준비 중 강연방 조회

**GET** `/api/v1/rooms/home?readyLimit=3` — HOST

로그인한 강연자가 소유한 진행 중인 방과 준비 중인 방을 같은 시점 기준으로 조회한다. `liveRooms`는 시작 일시 내림차순, `readyRooms.rooms`는 생성 일시 내림차순이다. 각 목록에서 시각이 같으면 방 ID가 큰 방을 먼저 반환한다. 응답 시각은 UTC다.

`readyLimit`은 이번에 표시할 준비 중인 방의 개수다. 기본값은 3이며, 홈 탭으로 돌아오면 클라이언트는 직전에 펼친 개수(예: 6)를 보낸다. 진행 중인 방은 모두 반환한다.

```json
{
  "data": {
    "liveRooms": [
      { "roomId": 10, "title": "Redis 오전반", "startedAt": "2026-11-21T00:00:00Z" }
    ],
    "readyRooms": {
      "rooms": [
        { "roomId": 11, "title": "Redis 오후반", "createdAt": "2026-11-20T06:00:00Z" }
      ],
      "nextCursor": null
    }
  }
}
```

진행 중인 방과 준비 중인 방이 모두 0개이면 `200 OK`와 `{"data":{"liveRooms":[],"readyRooms":{"rooms":[],"nextCursor":null}}}`을 반환한다.

**GET** `/api/v1/rooms/ready?cursor=...` — HOST

‘더 보기’는 이전 응답의 `nextCursor`를 그대로 보낸다. 응답의 `data`는 위의 `readyRooms`와 같은 형식이다. 서버는 마지막으로 표시한 방의 생성 일시와 ID를 기준으로 그다음 준비 중인 방을 최대 3개 반환한다. 앞쪽에 새 방이 생기거나 기준이 된 방의 상태가 바뀌어도 이어서 조회할 수 있다. 커서를 생략하면 첫 3개를 조회한다. 다음 방이 없으면 `nextCursor`는 `null`이다.

두 API 모두 로그인 세션이 없으면 `401 UNAUTHENTICATED`, 강연자 권한이 없으면 `403 FORBIDDEN`을 반환한다. 잘못된 조회 개수나 커서에는 `400 INVALID_INPUT`을 반환한다.

### [ROOM-08] 내 강연 기록

강연자 메뉴의 `/host/rooms/history`에서 로그인한 강연자 본인의 종료된 강연을 조회한다.

| 요청 | 응답의 `data` | 사용 시점 |
| --- | --- | --- |
| `GET /api/v1/rooms/history` | `rooms`, `nextCursor`, `totalCount`, `serverTime` | 메뉴 진입·새로고침 시 최근 10개. 더 보기는 직전 응답의 `nextCursor`를 `?cursor=...`로 전달 |
| `GET /api/v1/rooms/history/latest` | `totalCount` | 다른 탭에서 돌아왔을 때 새로 종료된 방이 있는지 확인 |
| `GET /api/v1/rooms/history/statuses?roomIds=21,15` | `rooms`의 각 항목에 `roomId`·`reportStatus`, 응답에 `serverTime` | 화면에 불러온 강연의 보고서 상태를 선택해서 갱신 |

목록의 방마다 `roomId`, `title`, `endedAt`, `reportStatus`를 반환한다. `endedAt` 내림차순으로 정렬하며, 같은 시각이면 ID가 큰 방이 먼저 온다. 종료 시각이 기록되지 않은 종료 방은 마지막에 표시한다. 다음 페이지가 없으면 `nextCursor`는 `null`이다. `totalCount`는 본인이 종료한 전체 방 수이고, 시각은 UTC다.

- `reportStatus`는 미접수일 때 `null`이며, 접수된 경우 `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED` 중 하나다. 강연 기록은 모든 행에서 열 수 있다. 완료된 보고서는 목록에서 바로 열 수 있고, 미접수 보고서의 수동 생성은 해당 강연 종료 화면에서 제공한다.
- 목록과 배치 상태 응답은 결과가 미확정인 방에만 `reportUnconfirmed: true`를 추가한다. 이때 `reportStatus`는 `FAILED`이고 목록에는 ‘보고서 결과 확인 중’으로 표시한다.
- 목록이 보이는 동안 대기·생성 중이거나 결과가 미확정인 보고서를 5초마다 확인한다. 미접수 상태이고 종료 후 2초가 지나지 않았으면 `serverTime` 기준으로 남은 시간 뒤 그 방만 한 번 더 확인한다. 뒤로 가면 목록과 스크롤을 복원하고 열었던 강연의 상태만 즉시 확인한다.
- 탭 복귀 시 새로 종료된 방이 있으면 **새 강연 기록 보기**를 제공한다. 이 버튼을 눌렀을 때 최신 10개로 이동한다. 갱신 오류가 발생해도 기존 목록과 읽던 위치를 유지한다.

세 API 모두 강연자 권한이 필요하다. 세션이 없으면 `401 UNAUTHENTICATED`, 권한이 없으면 `403 FORBIDDEN`이다. 잘못된 커서나 상태 조회 ID(양의 정수 1~50개 필요)는 `400 INVALID_INPUT`이다. 상태 조회는 요청한 ID 중 본인 소유의 종료된 방만 반환한다.

### [ROOM-08] 진행 중인 내 강연방 조회

**GET** `/api/v1/rooms/live` — HOST

로그인한 강연자가 소유하고 상태가 `LIVE`인 방을 조회한다. 시작 일시 내림차순으로 반환하며, 시작 일시가 같으면 방 ID가 큰 순서로 반환한다. 시작 일시는 UTC 기준이다. 결과가 없으면 `200 OK`와 `{"data":[]}`를 반환한다.

```json
{
  "data": [
    { "roomId": 10, "title": "Redis 실습 강연", "startedAt": "2026-11-17T01:00:00Z" }
  ]
}
```

인증 세션이 없으면 `401 UNAUTHENTICATED`, 강연자 권한이 없으면 `403 FORBIDDEN`을 반환한다.

### [ROOM-01] 강연방 생성 및 참여 코드 발급

**POST** `/api/v1/rooms` — HOST

Request

```json
{ "title": "Redis 실습 강연", "description": "Redis와 MySQL의 역할을 알아봅니다." }
```

Response — 201 Created

```json
{
  "data": {
    "roomId": 10,
    "title": "Redis 실습 강연",
    "description": "Redis와 MySQL의 역할을 알아봅니다.",
    "status": "READY",
    "joinCode": "AB7KQ9",
    "createdAt": "2026-11-17T00:00:00Z"
  }
}
```

규칙: 생성자는 현재 로그인한 강연자, 강연방 ID·참여 코드는 서버가 생성(코드 중복 불가), 최초 상태 READY, 생성 시 STT·채팅 분석은 시작하지 않음.
오류: `400 INVALID_INPUT`, `401 UNAUTHENTICATED` · 엔터티: `lecture_rooms`

### [ROOM-10] 준비 중인 강연방 영구 삭제

**DELETE** `/api/v1/rooms/{roomId}` — HOST, 강연방 소유자. 요청 본문 없이 세션 쿠키와 CSRF 헤더를 보낸다.

| 응답 | 의미 |
| --- | --- |
| `204 No Content` | 방과 해당 방의 청중 입장 기록을 한 트랜잭션에서 삭제했다. |
| `401 UNAUTHENTICATED` / `403 FORBIDDEN` | 로그인이 필요하거나 방을 삭제할 권한이 없다. |
| `404 ROOM_NOT_FOUND` | 해당 ID의 방이 없다. |
| `409 INVALID_ROOM_STATUS` | 방이 이미 시작되었거나 종료되어 삭제할 수 없다. |

청중 입장과 강연 시작·삭제는 같은 방의 행을 잠근다. 입장이 먼저 완료되면 그 입장 기록도 삭제한다. 시작이 먼저 완료되면 삭제 요청은 `409`를 반환한다. 삭제를 커밋한 뒤 해당 방의 상태 채널로 [ROOM_DELETED 이벤트](dash.md#9-6-강연-상태-변경-이벤트--subscribe-topicroomsroomidstatus)를 보낸다. 이후 초대 코드를 공개 조회하면 `404 INVALID_JOIN_CODE`를 반환한다.

삭제 응답을 받지 못한 강연자 화면은 `GET /api/v1/rooms/{roomId}`로 상태를 확인한다. 강연자가 다시 삭제를 확정할 수 있는 상태는 `READY`뿐이다.

### [ROOM-02] 참여 링크 및 QR코드 생성 정보 조회

**GET** `/api/v1/rooms/{roomId}/invitation` — HOST, 강연방 소유자

Response — 200 OK

```json
{
  "data": {
    "roomId": 10,
    "joinCode": "AB7KQ9",
    "joinUrl": "https://react.example/join/AB7KQ9",
    "qrText": "https://react.example/join/AB7KQ9"
  }
}
```

규칙: 기존 참여 코드 재사용, QR 은 `qrText` 로 프론트에서 렌더링(이미지는 DB 에 저장하지 않음), 참여 링크에 관리자 인증 정보 포함 금지.
오류: `403 FORBIDDEN`, `404 ROOM_NOT_FOUND`

### [ROOM-03] 강연자용 강연방 상세 조회

강연자 화면 주소는 `/host/rooms/{roomId}`로 유지한다. 같은 주소에서 서버가 반환한 방 상태에 따라 준비 화면(`READY`), 강연 진행 화면(`LIVE`), 종료 화면(`ENDED`)을 표시한다.

강연자 통합 화면에서 강연 시작·종료, STT 녹음, AI 청중 의견 요약과 근거 원문, 실시간 채팅을 이용한다. 강연이 끝나면 채팅을 읽기 전용으로 표시한다. 일반 투표와 이해도 체크 조작 화면은 다음 단계에서 연결한다. [채팅 조회·전송 규칙](chat.md)

`LIVE` 화면 중앙에는 AI 청중 의견 요약을 기본으로 펼쳐 둔다. STT 녹음 버튼과 저장 상태는 발언 원문을 접어도 보이며, 저장 실패 시 같은 위치에서 재시도할 수 있다. 최근 발언 원문은 기본으로 접고 강연자가 필요할 때 펼쳐 확인한다.

`ENDED` 화면은 종료 시각과 AI 청중 의견 요약을 보여준다. 강연자가 ‘저장된 발언 보기’를 펼치면 해당 방의 모든 STT 세션에 저장된 발언과 실제 누락 구간을 시작 시각순으로 함께 보여준다. 발언 사이의 침묵만으로 누락 구간을 만들지 않는다. 녹음 조작과 참여 QR은 숨긴다.

**GET** `/api/v1/rooms/{roomId}` — HOST, 강연방 소유자

Response — 200 OK

```json
{
  "data": {
    "roomId": 10,
    "title": "Redis 실습 강연",
    "description": "Redis와 MySQL의 역할을 알아봅니다.",
    "status": "READY",
    "joinCode": "AB7KQ9",
    "createdAt": "2026-11-17T00:00:00Z",
    "startedAt": null,
    "endedAt": null
  }
}
```

규칙: 자신의 강연방만 조회, 다른 강연자의 강연방 접근 차단. 오류: `403 FORBIDDEN`, `404 ROOM_NOT_FOUND`

### [ROOM-03] 청중 입장 전 공개 강연방 정보 조회

**GET** `/api/v1/public/rooms/{joinCode}` — 인증 불필요

Response — 200 OK

```json
{
  "data": {
    "roomId": 10,
    "title": "Redis 실습 강연",
    "description": "Redis와 MySQL의 역할을 알아봅니다.",
    "status": "READY",
    "canJoin": true
  }
}
```

규칙: READY·LIVE 에서는 입장 가능, ENDED 에서는 `canJoin=false`, 비공개 관리 정보는 반환하지 않음. 오류: `404 INVALID_JOIN_CODE`

### [ROOM-04] 강연 시작

**POST** `/api/v1/rooms/{roomId}/start` — HOST, 강연방 소유자

Response — 200 OK

```json
{ "data": { "roomId": 10, "status": "LIVE", "startedAt": "2026-11-17T01:00:00Z" } }
```

규칙: READY 에서만 시작, 시작 시각은 서버 기준, LIVE 부터 채팅·이해도·투표 응답 허용, STT 음성 인식은 브라우저에서 별도로 시작, 시작 이벤트를 WebSocket 으로 전달.

시작 요청 중에는 중복 클릭을 막는다. 응답이 불확실하면 방 상세를 조회하고 `LIVE`이면 진행 화면, `ENDED`이면 종료 화면을 표시한다. `READY`에서만 ‘강연 시작 다시 시도’를 제공하고 조회도 실패하면 ‘상태 다시 확인’을 제공한다.
오류: `403 FORBIDDEN`, `409 INVALID_ROOM_STATUS` · 엔터티: `lecture_rooms`

### [ROOM-04] 강연 종료

**POST** `/api/v1/rooms/{roomId}/end` — HOST, 강연방 소유자

Response — 200 OK

```json
{
  "data": {
    "roomId": 10,
    "status": "ENDED",
    "endedAt": "2026-11-17T02:00:00Z",
    "reportReady": false,
    "pendingFinalization": true
  }
}
```

규칙: LIVE 에서만 종료, 종료 후 신규 채팅·투표 응답(이해도 체크 포함) 차단, 열려 있는 투표 자동 마감(`close_reason=ROOM_ENDED`), 종료 시각 서버 저장, 마지막 STT 기록 및 AI 분석 작업 정리 후 회고 생성 가능 상태로 전환.

브라우저는 STT 인식을 멈추고 마지막 확정 발언을 저장한 뒤 STT 세션 종료와 강연 종료를 차례로 요청한다. 방 종료가 DB에 반영되면 서버가 보고서를 자동 접수한다. 접수와 마지막 채팅 분석 확정이 모두 끝나면 해당 방의 보고서 한 건을 생성한다. 자동 접수에 실패해도 방은 `ENDED`를 유지한다. [보고서 접수 명세](report.md)

강연 종료 API는 같은 트랜잭션에서 해당 방의 `RECORDING` 세션을 `STOPPED`로 바꾸고 열린 STT 누락 구간을 닫는다. 새로 닫는 세션과 구간에는 강연방의 `endedAt`을 기록한다. 이미 닫힌 기록은 기존 종료 시각을 유지한다.

강연 진행 화면에서 ‘강연 종료’를 누르면 확인창을 연다. 확인창이 열려 있는 동안 강연은 계속되며, 녹음 중이면 발언 인식과 저장도 계속한다. ‘계속 진행’은 확인창만 닫는다. ‘강연 종료’를 확정하면 위 순서에 따라 진행 중인 녹음을 멈추고 남은 확정 발언을 저장한 뒤 STT 세션과 강연방을 차례로 종료한다.

강연자가 강연 종료를 눌렀을 때 마지막 확정 발언의 서버 저장을 확인하지 못하면 화면은 강연 종료 요청을 보류하고 저장 재시도를 안내한다. 재시도에는 해당 발언의 기존 `segmentKey`를 사용한다. 방은 `LIVE`로 유지되며 청중은 계속 채팅할 수 있다.

강연 종료를 확정한 상태에서는 ‘재시도 후 강연 종료’를 표시한다. 재시도에 성공하면 STT 세션을 종료하고 강연방 종료를 이어서 요청한다. ‘녹음 중지’만 선택한 상태에서는 ‘저장 재시도’ 성공 후 STT 세션만 종료하고 강연방은 `LIVE`로 유지한다.

마지막 발언 저장과 STT 종료를 마친 뒤 강연방 종료 요청의 응답이 불확실하면 방 상세 API로 상태를 조회한다. `ENDED`이면 종료 화면으로 이동한다. `LIVE`이면 ‘강연 종료 다시 시도’로 방 종료 요청만 보낸다. 상태 조회도 실패하면 ‘상태 다시 확인’으로 방 상세를 다시 조회한다.

강연 종료를 확정한 뒤 같은 탭을 새로고침하면 해당 강연자와 방의 종료 진행 상태를 복원하고 먼저 방 상태를 조회한다. `ENDED`이면 종료 화면을 표시한다. `LIVE`이면 남은 발언의 저장을 복구하고 ‘강연 종료 계속하기’를 표시한다. 저장 복구만으로 방을 닫지 않으며, 강연자가 이 버튼을 누르면 남은 기록 저장과 STT 세션 종료를 확인한 뒤 방 종료를 요청한다.
`reportReady`·`pendingFinalization` 은 ERD 컬럼이 아니라 처리 상태를 기준으로 계산하는 응답값이다.

PC와 모바일 모두 강연 종료 안내 바로 아래, AI 요약과 채팅 위에 보고서 상태와 버튼을 배치한다. 모바일에서 AI 요약과 채팅 탭을 바꿔도 같은 위치에 표시한다. 종료 화면은 생성 상태만 조회한다. 완료되면 그 자리에 ‘회고 보고서 보기’ 버튼을 표시하고, 강연자가 누르면 보고서 페이지에서 저장된 본문을 불러온다. [보고서 조회 명세](report.md#report-06-ai-회고-보고서-조회)
오류: `403 FORBIDDEN`, `409 INVALID_ROOM_STATUS`

### [ROOM-05] 청중 비회원 입장

**POST** `/api/v1/public/rooms/{joinCode}/participants` — HOST 로그인 불필요

Request Headers

- `Content-Type: application/json`
- `X-CSRF-TOKEN: <발급된 CSRF 토큰>`
- `Idempotency-Key: <UUID v4>`

Request

```json
{ "nickname": "청중A" }
```

Response — 최초 입장 시 201 Created

```json
{
  "data": {
    "participantId": 101,
    "roomId": 10,
    "nickname": "청중A",
    "roomStatus": "LIVE",
    "joinedAt": "2026-11-17T01:00:10Z"
  }
}
```

참여 쿠키 예시:

```http
Set-Cookie: PARTICIPANT_SESSION_10=<token>; HttpOnly; Secure; SameSite=Lax; Path=/
```

규칙:

- 회원가입 없이 QR 링크와 닉네임으로 입장한다.
- 동일 닉네임을 사용하는 서로 다른 참여자를 허용한다.
- 참여자 ID와 인증 토큰은 서버에서 생성한다.
- 참여 토큰 원문은 DB에 저장하지 않고 SHA-256 해시만 저장한다.
- 방별 인증 쿠키 이름은 `PARTICIPANT_SESSION_{roomId}`이다.
- 쿠키의 `{roomId}`는 숫자로 된 강연방 ID이다.
- 동일 브라우저에서 여러 강연방의 쿠키를 독립적으로 유지한다.
- A방 → B방 → A방으로 복귀해도 A방의 기존 참여자 ID를 재사용한다.
- 기존에 발급된 유효한 방별 쿠키가 있으면 `200 OK`와 기존 참여자 정보를 반환한다.
- 기존 쿠키가 없으면 신규 입장 요청에 UUID v4 형식의 `Idempotency-Key`가 필요하다.
- 같은 입장 시도를 재전송할 때는 동일한 키를 사용한다.
- 동일한 방·키에 대한 중복 요청은 기존 참여자 ID와 동일한 인증 토큰을 재발급한다(`200 OK`).
- 최초 성공한 신규 입장은 `201 Created`를 반환한다.
- 동일 키의 동시 요청은 DB의 토큰 해시 UNIQUE 제약으로 중복 생성을 방지한다.
- 동일한 키를 다른 강연방에서 사용하더라도 토큰 생성 범위에 roomId가 포함되어 방별 토큰은 서로 다르다.
- 유효기간이 지난 키를 재사용하면 `409 CONFLICT`를 반환한다.
- ENDED 강연에서는 신규 참여자를 생성하지 않는다.
- 유효한 기존 참여자의 재입장 및 참여 정보 조회는 허용한다.
- 세션 유효기간은 최초 입장 시각 기준으로 계산하며 `PARTICIPANT_SESSION_TTL`로 설정한다.
- 클라이언트는 같은 입장 시도의 재시도에서 키를 유지하고, 새로운 입장 시도에서는 새 키를 생성해야 한다.
- `Idempotency-Key`도 인증 쿠키 재발급에 이용될 수 있으므로 요청 로그나 외부에 노출하지 않는다.

오류:

- `400 INVALID_NICKNAME`: 닉네임이 올바르지 않음
- `400 INVALID_INPUT`: 신규 입장 키 누락 또는 형식 오류
- `404 INVALID_JOIN_CODE`: 유효하지 않은 강연 참여 코드
- `409 ROOM_ENDED`: 종료된 강연에 신규 입장 시도
- `409 CONFLICT`: 만료된 입장 키 재사용

엔터티: `lecture_rooms`, `lecture_participants`

### [ROOM-05] 내 청중 참여 정보 조회

**GET** `/api/v1/rooms/{roomId}/participants/me` — PARTICIPANT

Response — 200 OK

```json
{
  "data": {
    "participantId": 101,
    "roomId": 10,
    "nickname": "청중A",
    "joinedAt": "2026-11-17T01:00:10Z"
  }
}
```

규칙: 새로고침 후 기존 참여 정보 확인, 참여자 식별 정보로 중복 집계 방지, 다른 강연방의 참여자로 인증되지 않음. 요청에 포함된 해당 방의 `PARTICIPANT_SESSION_{roomId}` 쿠키를 검증한다.

오류: `401 UNAUTHENTICATED`, `403 FORBIDDEN`

### [ROOM-06, ROOM-07] 권한 및 강연 종료 상태 검증

별도 공개 API 없음. 모든 관련 API 와 WebSocket 처리 단계에 적용한다.

- HOST 는 해당 강연방의 `host_user_id` 와 인증 사용자 일치를 확인한다.
- PARTICIPANT 는 해당 강연방에 참여한 사용자인지 확인한다.
- 다른 강연방의 데이터 조회·등록·수정을 차단한다.
- ENDED 에서는 신규 채팅·이해도·투표 응답을 거부하고, 기존 데이터 조회는 권한에 따라 허용한다.
- WebSocket 연결뿐 아니라 구독·메시지 전송 시에도 권한을 검증한다.
```
