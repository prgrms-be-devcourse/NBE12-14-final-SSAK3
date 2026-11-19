# ROOM — 강연방 관리

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

권장 처리 순서: ① 브라우저 STT 중지 → ② 마지막 확정 전사 저장 → ③ STT 세션 종료 API → ④ 강연 종료 API → ⑤ 잔여 AI 분석 완료 또는 실패 확정 → ⑥ 회고 보고서 생성.
`reportReady`·`pendingFinalization` 은 ERD 컬럼이 아니라 처리 상태를 기준으로 계산하는 응답값이다.
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
