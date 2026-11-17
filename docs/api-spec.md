# RE:ACT — MVP API 명세서 v1.1

기준 문서는 `docs/feature-spec.md`(기능 정의서, P0), DB 기준은 `docs/db/erd.dbml`(ERD v1.1, 16개 테이블)과 Flyway 마이그레이션이다.
문서끼리 어긋나면 DB 는 Flyway 마이그레이션을, 이 명세의 REST·WebSocket 형식을 우선하고, 실제 충돌은 PR 에서 팀원과 조율한다.

상태: 초기 개발 명세. 인증·실시간 프로토콜(세션 쿠키, STOMP)과 일부 정책은 팀 합의가 필요하다(§14).

## 0. 개요

### 서비스 목적

강연자의 실제 발언(STT)과 청중의 채팅·이해도 반응·투표 결과를 실시간으로 수집하고, AI 가 청중 의견을 주제별로 요약한다. 강연 종료 후에는 STT 발언과 청중 반응을 시간대별로 연결한 AI 회고 보고서를 제공한다.

### 주요 설계 원칙

- 강연자와 청중 모두 채팅할 수 있다.
- AI 청중 의견 요약에는 청중이 작성한 채팅만 포함한다.
- AI 는 대표 질문을 생성하지 않고, 질문·의견·요청·이해 부족 표현을 주제별로 요약한다.
- STT 는 강연자의 발언을 강연 중 지속적으로 텍스트로 변환해 저장한다.
- 강연 발언과 청중 반응은 공통 시간 구간을 기준으로 연결한다.
- 같은 시간대에 발생했다는 이유만으로 특정 발언이 이해 부족의 원인이라고 단정하지 않는다.
- 분석 결과에는 실제 원문 메시지를 연결한다.
- AI 실패가 채팅·이해도·투표 기능에 영향을 주지 않아야 한다.
- 강연 종료 후 새 채팅·이해도·투표 응답은 차단한다.

---

## 1. 공통 API 규약

### 1-1. 기본 정보

| 항목 | 규격 |
| --- | --- |
| Base URL | `/api/v1` |
| WebSocket Endpoint | `/ws` |
| 데이터 형식 | JSON |
| 요청·응답 필드 | camelCase |
| DB 컬럼 | snake_case |
| 날짜·시간 | ISO-8601, UTC 오프셋 포함 |
| 강연자 역할 | HOST |
| 청중 역할 | PARTICIPANT |
| 강연 상태 | READY / LIVE / ENDED |
| 인증 방식 | 강연자 세션 쿠키 + 청중 참여 쿠키 (제안) |
| 실시간 메시지 | STOMP 주소 기준 예시 |

> 인증 방식과 STOMP 사용 여부는 팀에서 최종 확정해야 한다. JWT 또는 순수 WebSocket 을 사용하면 인증·이벤트 계약을 그에 맞게 변경해야 한다.

### 1-2. 공통 오류 응답

```json
{
  "code": "ROOM_NOT_LIVE",
  "message": "진행 중인 강연이 아닙니다.",
  "details": null,
  "timestamp": "2026-11-17T01:00:00Z"
}
```

| HTTP 상태 코드 | 의미 |
| --- | --- |
| 400 | 입력값 오류 |
| 401 | 인증 정보 없음 또는 인증 실패 |
| 403 | 접근 권한 없음 |
| 404 | 리소스 없음 |
| 409 | 중복 요청 또는 상태 충돌 |
| 429 | 요청 횟수 제한 |
| 500 | 서버 내부 오류 |
| 503 | 외부 서비스 장애 |

### 1-3. 인증·접근 권한

**HOST**

- 회원가입 및 로그인한 강연자
- 자신의 강연방 생성·조회·시작·종료
- STT 관리, 투표 생성·종료
- 강연자 대시보드 조회, AI 요약 및 회고 보고서 조회
- 채팅 송수신

**PARTICIPANT**

- 참여 코드로 비회원 입장
- 해당 강연방 채팅 송수신
- 본인 이해 상태 등록·변경, 투표 응답
- 허용된 채팅·투표 정보 조회

강연자와 청중 모두 클라이언트에서 임의로 `userId`, `participantId`, `senderType` 을 지정해 권한을 획득할 수 없다.

---

## 2. AUTH — 강연자 인증

### [AUTH-01] 강연자 회원가입

**POST** `/api/v1/auth/signup` — 인증 불필요

Request

```json
{ "email": "host@example.com", "password": "ExamplePassword123!", "name": "김강연" }
```

Response — 201 Created

```json
{ "userId": 1, "email": "host@example.com", "name": "김강연", "createdAt": "2026-11-17T00:00:00Z" }
```

규칙: 이메일 형식 검증, 중복 가입 차단, 비밀번호 정책 검증, 비밀번호 해시 저장, 비밀번호·해시는 응답에 포함하지 않음, 가입 후 자동 로그인은 하지 않는 방식을 제안한다.
오류: `400 INVALID_INPUT`, `409 EMAIL_ALREADY_EXISTS` · 엔터티: `users`

### [AUTH-02] 강연자 로그인

**POST** `/api/v1/auth/login` — 인증 불필요

Request

```json
{ "email": "host@example.com", "password": "ExamplePassword123!" }
```

Response — 200 OK

```json
{ "userId": 1, "email": "host@example.com", "name": "김강연", "authenticated": true }
```

응답 헤더 예시: `Set-Cookie: HOST_SESSION=...; HttpOnly; Secure; SameSite=Lax`
규칙: 이메일로 사용자 조회, 암호화된 비밀번호 검증, 성공 시 세션 생성, 실패 시 인증 정보 미발급.
오류: `401 INVALID_CREDENTIALS` · 엔터티: `users`

### [AUTH-03] 현재 로그인 사용자 조회

**GET** `/api/v1/auth/me` — HOST

Response — 200 OK

```json
{ "userId": 1, "email": "host@example.com", "name": "김강연" }
```

규칙: 서버 인증 정보로 현재 사용자 식별(클라이언트가 보낸 userId 를 신뢰하지 않음), 비밀번호 정보 노출 금지.
오류: `401 UNAUTHENTICATED`

### [AUTH-04] 강연자 로그아웃

**POST** `/api/v1/auth/logout` — HOST — Response 204 No Content

규칙: 서버 세션 무효화, 인증 쿠키 만료, 로그아웃과 강연 종료는 별개이며 기존 강연 기록은 삭제하지 않음.

---

## 3. ROOM — 강연방 관리

### [ROOM-01] 강연방 생성 및 참여 코드 발급

**POST** `/api/v1/rooms` — HOST

Request

```json
{ "title": "Redis 실습 강연", "description": "Redis와 MySQL의 역할을 알아봅니다." }
```

Response — 201 Created

```json
{
  "roomId": 10,
  "title": "Redis 실습 강연",
  "description": "Redis와 MySQL의 역할을 알아봅니다.",
  "status": "READY",
  "joinCode": "AB7KQ9",
  "createdAt": "2026-11-17T00:00:00Z"
}
```

규칙: 생성자는 현재 로그인한 강연자, 강연방 ID·참여 코드는 서버가 생성(코드 중복 불가), 최초 상태 READY, 생성 시 STT·채팅 분석은 시작하지 않음.
오류: `400 INVALID_INPUT`, `401 UNAUTHENTICATED` · 엔터티: `lecture_rooms`

### [ROOM-02] 참여 링크 및 QR코드 생성 정보 조회

**GET** `/api/v1/rooms/{roomId}/invitation` — HOST, 강연방 소유자

Response — 200 OK

```json
{
  "roomId": 10,
  "joinCode": "AB7KQ9",
  "joinUrl": "https://react.example/join/AB7KQ9",
  "qrText": "https://react.example/join/AB7KQ9"
}
```

규칙: 기존 참여 코드 재사용, QR 은 `qrText` 로 프론트에서 렌더링(이미지는 DB 에 저장하지 않음), 참여 링크에 관리자 인증 정보 포함 금지.
오류: `403 FORBIDDEN`, `404 ROOM_NOT_FOUND`

### [ROOM-03] 강연자용 강연방 상세 조회

**GET** `/api/v1/rooms/{roomId}` — HOST, 강연방 소유자

Response — 200 OK

```json
{
  "roomId": 10,
  "title": "Redis 실습 강연",
  "description": "Redis와 MySQL의 역할을 알아봅니다.",
  "status": "READY",
  "joinCode": "AB7KQ9",
  "createdAt": "2026-11-17T00:00:00Z",
  "startedAt": null,
  "endedAt": null
}
```

규칙: 자신의 강연방만 조회, 다른 강연자의 강연방 접근 차단. 오류: `403 FORBIDDEN`, `404 ROOM_NOT_FOUND`

### [ROOM-03] 청중 입장 전 공개 강연방 정보 조회

**GET** `/api/v1/public/rooms/{joinCode}` — 인증 불필요

Response — 200 OK

```json
{
  "roomId": 10,
  "title": "Redis 실습 강연",
  "description": "Redis와 MySQL의 역할을 알아봅니다.",
  "status": "READY",
  "canJoin": true
}
```

규칙: READY·LIVE 에서는 입장 가능, ENDED 에서는 `canJoin=false`, 비공개 관리 정보는 반환하지 않음. 오류: `404 INVALID_JOIN_CODE`

### [ROOM-04] 강연 시작

**POST** `/api/v1/rooms/{roomId}/start` — HOST, 강연방 소유자

Response — 200 OK

```json
{ "roomId": 10, "status": "LIVE", "startedAt": "2026-11-17T01:00:00Z" }
```

규칙: READY 에서만 시작, 시작 시각은 서버 기준, LIVE 부터 채팅·이해도·투표 응답 허용, STT 음성 인식은 브라우저에서 별도로 시작, 시작 이벤트를 WebSocket 으로 전달.
오류: `403 FORBIDDEN`, `409 INVALID_ROOM_STATUS` · 엔터티: `lecture_rooms`

### [ROOM-04] 강연 종료

**POST** `/api/v1/rooms/{roomId}/end` — HOST, 강연방 소유자

Response — 200 OK

```json
{
  "roomId": 10,
  "status": "ENDED",
  "endedAt": "2026-11-17T02:00:00Z",
  "reportReady": false,
  "pendingFinalization": true
}
```

규칙: LIVE 에서만 종료, 종료 후 신규 채팅·이해도 변경·투표 응답 차단, 열려 있는 투표 자동 종료, 종료 시각 서버 저장, 마지막 STT 기록 및 AI 분석 작업 정리 후 회고 생성 가능 상태로 전환.

권장 처리 순서: ① 브라우저 STT 중지 → ② 마지막 확정 전사 저장 → ③ STT 세션 종료 API → ④ 강연 종료 API → ⑤ 잔여 AI 분석 완료 또는 실패 확정 → ⑥ 회고 보고서 생성.
`reportReady`·`pendingFinalization` 은 ERD 컬럼이 아니라 처리 상태를 기준으로 계산하는 응답값이다.
오류: `403 FORBIDDEN`, `409 INVALID_ROOM_STATUS`

### [ROOM-05] 청중 비회원 입장

**POST** `/api/v1/public/rooms/{joinCode}/participants` — 인증 불필요

Request

```json
{ "nickname": "청중A" }
```

Response — 201 Created

```json
{ "participantId": 101, "roomId": 10, "nickname": "청중A", "roomStatus": "LIVE", "joinedAt": "2026-11-17T01:00:10Z" }
```

참여 쿠키 예시: `Set-Cookie: PARTICIPANT_SESSION=...; HttpOnly; Secure`
규칙: 회원가입 없이 참여, 동일 닉네임 허용, 참여자 ID 는 서버 생성, 참여 토큰 원문은 DB 에 저장하지 않고 해시만 저장, 기존 유효 세션이면 동일 참여자 정보 재사용(재입장 시 `200 OK` 가능), ENDED 강연에는 신규 입장 불가.
오류: `400 INVALID_NICKNAME`, `404 INVALID_JOIN_CODE`, `409 ROOM_ENDED` · 엔터티: `lecture_rooms`, `lecture_participants`

### [ROOM-05] 내 청중 참여 정보 조회

**GET** `/api/v1/rooms/{roomId}/participants/me` — PARTICIPANT

Response — 200 OK

```json
{ "participantId": 101, "roomId": 10, "nickname": "청중A", "joinedAt": "2026-11-17T01:00:10Z" }
```

규칙: 새로고침 후 기존 참여 정보 확인, 참여자 식별 정보로 중복 집계 방지, 다른 강연방의 참여자로 인증되지 않음. 오류: `401 UNAUTHENTICATED`, `403 FORBIDDEN`

### [ROOM-06, ROOM-07] 권한 및 강연 종료 상태 검증

별도 공개 API 없음. 모든 관련 API 와 WebSocket 처리 단계에 적용한다.

- HOST 는 해당 강연방의 `host_user_id` 와 인증 사용자 일치를 확인한다.
- PARTICIPANT 는 해당 강연방에 참여한 사용자인지 확인한다.
- 다른 강연방의 데이터 조회·등록·수정을 차단한다.
- ENDED 에서는 신규 채팅·이해도·투표 응답을 거부하고, 기존 데이터 조회는 권한에 따라 허용한다.
- WebSocket 연결뿐 아니라 구독·메시지 전송 시에도 권한을 검증한다.

---

## 4. CHAT — 실시간 채팅

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

오류 이벤트는 발신자 전용 채널 `/user/queue/errors` 로 전달하는 방식을 제안한다.

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
  "hasNext": false
}
```

규칙: 해당 강연방 메시지만 조회, 커서 기반 페이지 조회, 응답은 최신 메시지부터 정렬하는 방식을 제안한다, 종료된 강연도 기존 메시지 조회 가능, AI 전처리 제외 여부와 무관하게 원문 조회 가능.
오류: `401 UNAUTHENTICATED`, `403 FORBIDDEN`, `404 ROOM_NOT_FOUND`

---

## 5. FEED — 이해도 신호

### [FEED-01, 02, 03, 05] 이해 상태 등록 및 변경

**PUT** `/api/v1/rooms/{roomId}/understanding/me` — PARTICIPANT

Request

```json
{ "status": "CONFUSED" }
```

| 값 | 의미 |
| --- | --- |
| UNDERSTOOD | 이해됨 |
| CONFUSED | 헷갈림 |
| NOT_UNDERSTOOD | 모르겠음 |

Response — 200 OK

```json
{
  "participantId": 101,
  "roomId": 10,
  "previousStatus": "UNDERSTOOD",
  "currentStatus": "CONFUSED",
  "changed": true,
  "updatedAt": "2026-11-17T01:12:00Z"
}
```

규칙:

- LIVE 에서만 변경 가능하며, 한 청중의 최신 이해 상태는 하나만 존재한다.
- 최초 선택 시 `participant_understandings` 를 생성하고, 이후 변경은 기존 레코드를 수정한다.
- 실제 변경이 발생하면 `understanding_histories` 를 추가한다.
- 동일 상태를 다시 요청하면 `changed=false` 이며 이력·응답 인원이 증가하지 않는다.
- 최신 상태 변경과 이력 저장은 하나의 트랜잭션이며, 동시 요청에도 정합성을 보장한다.

엔터티: `participant_understandings`, `understanding_histories`

### [FEED-02] 내 최신 이해 상태 조회

**GET** `/api/v1/rooms/{roomId}/understanding/me` — PARTICIPANT

```json
{ "participantId": 101, "status": "CONFUSED", "updatedAt": "2026-11-17T01:12:00Z" }
```

미응답이면 `{ "participantId": 101, "status": null, "updatedAt": null }`. 미선택은 모르겠음으로 간주하지 않는다. 자신의 최신 상태만 조회하며 재접속 시 복원에 활용할 수 있다.

### [FEED-04, 06] 강연방 이해도 집계 조회

**GET** `/api/v1/rooms/{roomId}/understanding/summary` — HOST, 강연방 소유자

```json
{
  "roomId": 10,
  "totalParticipants": 20,
  "respondedParticipants": 15,
  "unrespondedParticipants": 5,
  "responseRate": 75.0,
  "counts": { "UNDERSTOOD": 8, "CONFUSED": 5, "NOT_UNDERSTOOD": 2 },
  "percentagesOfAllParticipants": { "UNDERSTOOD": 40.0, "CONFUSED": 25.0, "NOT_UNDERSTOOD": 10.0 },
  "calculatedAt": "2026-11-17T01:12:01Z"
}
```

규칙: 전체 인원은 누적 고유 청중 기준, 응답 인원은 최신 이해 상태가 있는 고유 청중 수, 미응답 = 전체 − 응답, 상태 비율의 분모는 전체 참여 인원, 과거 변경 이력은 현재 집계에 중복 포함하지 않음, 현재 접속 인원과 누적 참여 인원은 구분한다.
엔터티: `lecture_participants`, `participant_understandings`

---

## 6. POLL — 실시간 투표

### [POLL-01] 투표 생성

**POST** `/api/v1/rooms/{roomId}/polls` — HOST, 강연방 소유자

Request

```json
{ "question": "Redis가 필요한 이유를 이해했나요?", "options": ["이해했어요", "조금 헷갈려요", "다시 설명이 필요해요"] }
```

Response — 201 Created

```json
{
  "pollId": 501,
  "roomId": 10,
  "question": "Redis가 필요한 이유를 이해했나요?",
  "status": "OPEN",
  "options": [
    { "optionId": 1, "content": "이해했어요", "displayOrder": 1 },
    { "optionId": 2, "content": "조금 헷갈려요", "displayOrder": 2 },
    { "optionId": 3, "content": "다시 설명이 필요해요", "displayOrder": 3 }
  ],
  "createdAt": "2026-11-17T01:20:00Z"
}
```

규칙: 강연자만, LIVE 에서만 생성, P0 은 단일 선택 투표, 선택지 2~5개, 강연방당 진행 중(OPEN) 투표 최대 1개를 제안한다, 생성 시 청중에게 WebSocket 이벤트 전달. 엔터티: `polls`, `poll_options`

### [POLL-01] 투표 종료

**POST** `/api/v1/rooms/{roomId}/polls/{pollId}/close` — HOST, 강연방 소유자

```json
{ "pollId": 501, "status": "CLOSED", "closedAt": "2026-11-17T01:23:00Z" }
```

규칙: OPEN 에서만 종료, 종료 후 추가 응답 차단, 강연 종료 시 진행 중인 투표도 자동 종료, 종료 이벤트 WebSocket 전파.
오류: `403 FORBIDDEN`, `404 POLL_NOT_FOUND`, `409 POLL_ALREADY_CLOSED`

### [POLL-02, 03] 청중 투표 응답

**POST** `/api/v1/rooms/{roomId}/polls/{pollId}/responses` — PARTICIPANT

```json
{ "optionId": 2 }
```

Response — 201 Created

```json
{ "pollId": 501, "participantId": 101, "optionId": 2, "respondedAt": "2026-11-17T01:21:00Z" }
```

규칙: 강연 LIVE 및 투표 OPEN 일 때만, 해당 강연방 참여자만, 한 청중은 투표당 한 번만 응답, 응답 후 선택지 변경 불가(P0 제안), 선택지는 해당 Poll 에 속해야 함, `(poll_id, participant_id)` UNIQUE, 응답 저장 후 실시간 결과 갱신.
오류: `400 INVALID_POLL_OPTION`, `403 FORBIDDEN`, `404 POLL_NOT_FOUND`, `409 ALREADY_VOTED`, `409 POLL_CLOSED`, `409 ROOM_ENDED` · 엔터티: `poll_responses`

### [POLL-01] 강연방 투표 목록 조회

**GET** `/api/v1/rooms/{roomId}/polls?status=OPEN|CLOSED` — HOST 또는 해당 PARTICIPANT

```json
{
  "polls": [
    {
      "pollId": 501,
      "question": "Redis가 필요한 이유를 이해했나요?",
      "status": "OPEN",
      "options": [
        { "optionId": 1, "content": "이해했어요" },
        { "optionId": 2, "content": "조금 헷갈려요" }
      ],
      "createdAt": "2026-11-17T01:20:00Z"
    }
  ]
}
```

규칙: 강연별 투표 목록 반환, 청중은 질문·선택지 조회 가능, 다른 강연의 투표 조회 금지, 종료된 투표도 기록으로 조회 가능.

### [POLL-03] 투표 결과 조회

**GET** `/api/v1/rooms/{roomId}/polls/{pollId}/results` — HOST, 강연방 소유자

```json
{
  "pollId": 501,
  "status": "OPEN",
  "totalParticipants": 20,
  "respondedParticipants": 12,
  "unrespondedParticipants": 8,
  "options": [
    { "optionId": 1, "content": "이해했어요", "count": 6, "percentageOfResponses": 50.0 },
    { "optionId": 2, "content": "조금 헷갈려요", "count": 4, "percentageOfResponses": 33.33 },
    { "optionId": 3, "content": "다시 설명이 필요해요", "count": 2, "percentageOfResponses": 16.67 }
  ],
  "calculatedAt": "2026-11-17T01:21:05Z"
}
```

규칙: 중복 응답 제외, 실제 `poll_responses` 기준 집계, 선택지별 비율의 분모는 해당 투표 응답자 수(이해도 비율의 분모와 구분), 강연방 소유권 검증.

---

## 7. STT — 강연자 발언 기록

### [STT-01] STT 세션 시작

**POST** `/api/v1/rooms/{roomId}/stt/sessions` — HOST, 강연방 소유자 — Request `{}`

```json
{ "sttSessionId": 701, "roomId": 10, "status": "RECORDING", "startedAt": "2026-11-17T01:00:02Z" }
```

규칙: LIVE 여야 함, 마이크 권한 확인은 프론트에서 수행, 브라우저 Web Speech API 로 STT 시작, 강연방당 동시에 하나의 RECORDING 세션만 허용, 중지 후 재시작하면 새 세션 생성 가능. 엔터티: `stt_sessions`

### [STT-02, 03] STT 확정 발언 저장

**POST** `/api/v1/rooms/{roomId}/stt/sessions/{sessionId}/segments` — HOST, 강연방 소유자

Request

```json
{
  "segmentKey": "browser-701-0001",
  "transcript": "Redis는 메모리 기반 데이터 저장소입니다.",
  "segmentStartAt": "2026-11-17T01:00:12Z",
  "segmentEndAt": "2026-11-17T01:00:19Z"
}
```

Response — 201 Created

```json
{
  "segmentId": 9001,
  "sttSessionId": 701,
  "segmentKey": "browser-701-0001",
  "transcript": "Redis는 메모리 기반 데이터 저장소입니다.",
  "segmentStartAt": "2026-11-17T01:00:12Z",
  "segmentEndAt": "2026-11-17T01:00:19Z",
  "receivedAt": "2026-11-17T01:00:20Z"
}
```

규칙: Web Speech API 에서 확정된 결과만 저장, 발언 텍스트는 공백 불가, 시작 시각이 종료 시각보다 늦을 수 없음, STT 세션과 강연방 일치 검증, 요청 시각은 브라우저가 기록한 추정 시각일 수 있어 서버 수신 시각을 별도 저장, 동일 `segmentKey` 재전송 시 중복 저장 차단(동일 키·동일 데이터는 기존 결과를 `200 OK` 로 반환하는 방식 권장), 종료된 강연에 새 발언 생성 금지.
제약: `UNIQUE(stt_session_id, segment_key)` · 엔터티: `stt_segments`

### [STT-04] STT 누락 구간 시작 기록

**POST** `/api/v1/rooms/{roomId}/stt/sessions/{sessionId}/gaps` — HOST, 강연방 소유자

Request

```json
{ "eventKey": "stt-701-gap-0001", "reason": "ERROR", "startedAt": "2026-11-17T01:25:00Z" }
```

Response — 201 Created

```json
{ "gapId": 8001, "sttSessionId": 701, "eventKey": "stt-701-gap-0001", "reason": "ERROR", "startedAt": "2026-11-17T01:25:00Z", "endedAt": null }
```

규칙: 브라우저 음성 인식 중단 시 기록, 기록이 없는 구간을 모두 오류로 간주하지 않음, `eventKey` 로 같은 이벤트 중복 저장 방지(동일 이벤트 재전송 시 기존 결과 반환, 같은 키로 다른 내용을 보내면 충돌 처리), 세션당 열린 누락 구간이 중복 생성되지 않도록 검증. 엔터티: `stt_gaps`

### [STT-04] STT 누락 구간 종료

**PATCH** `/api/v1/rooms/{roomId}/stt/sessions/{sessionId}/gaps/{gapId}/close` — HOST, 강연방 소유자

Request `{ "endedAt": "2026-11-17T01:25:15Z" }`

```json
{ "gapId": 8001, "startedAt": "2026-11-17T01:25:00Z", "endedAt": "2026-11-17T01:25:15Z", "reason": "ERROR" }
```

규칙: STT 가 정상 재시작된 시점에 종료, 종료 시각은 시작 시각보다 빠를 수 없음, 이미 닫힌 구간의 중복 처리 방지, 누락 구간은 회고 보고서에 별도 표시.

### [STT-01] STT 세션 종료

**POST** `/api/v1/rooms/{roomId}/stt/sessions/{sessionId}/stop` — HOST, 강연방 소유자

Request `{ "reason": "MANUAL_STOP" }`

```json
{ "sttSessionId": 701, "status": "STOPPED", "endedAt": "2026-11-17T02:00:00Z" }
```

규칙: 마지막 확정 전사 결과 저장 후 종료, 정상 종료는 STOPPED, 오류 종료는 ERROR 로 관리 가능, `reason` 은 ERD 에 저장 컬럼이 없는 참고 입력.

### [STT-05] 강연 발언 및 누락 구간 조회

**GET** `/api/v1/rooms/{roomId}/stt/segments?from=&to=` — HOST, 강연방 소유자

```json
{
  "roomId": 10,
  "segments": [
    {
      "segmentId": 9001,
      "sessionId": 701,
      "transcript": "Redis는 메모리 기반 데이터 저장소입니다.",
      "segmentStartAt": "2026-11-17T01:00:12Z",
      "segmentEndAt": "2026-11-17T01:00:19Z",
      "receivedAt": "2026-11-17T01:00:20Z"
    }
  ],
  "gaps": [
    { "gapId": 8001, "sessionId": 701, "reason": "ERROR", "startedAt": "2026-11-17T01:25:00Z", "endedAt": "2026-11-17T01:25:15Z" }
  ]
}
```

규칙: 해당 강연의 모든 STT 세션 기록을 통합 조회하고 발언 시각 순으로 정렬, 오류·누락 구간은 별도 반환, 발언이 없는 구간을 AI 로 임의 보완하지 않음, 다른 강연방 STT 기록 조회 금지.

---

## 8. AI-CHAT — 청중 의견 분석

### [AI-CHAT-01~05, 08] 주기적 청중 채팅 분석

서버 내부 스케줄러와 LLM API 호출. 별도 공개 Request API 는 없다.

실행 조건: 강연 LIVE, 분석 주기 도래, 분석 대상 청중 채팅 존재, 분석 대상 구간 확정.

정상 흐름: 채팅 DB 조회 → 강연자 메시지 제외 → 분석 구간 확정 → `ai_analyses` PENDING 생성 → PROCESSING 변경 → LLM API 호출 → 구조화된 결과 검증 → 유사 주제 그룹화·핵심 의견 요약 → 원문 메시지 연결 → 결과 DB 저장 → COMPLETED 변경 → 대시보드 알림.

LLM 출력 예시

```json
{
  "topics": [
    {
      "topic": "Redis 사용 이유",
      "summary": "Redis를 MySQL 대신 사용하는 이유가 궁금하다는 질문과 실제 사례를 요청하는 의견이 있었습니다.",
      "category": "MIXED",
      "sourceMessageIds": [1001, 1003]
    }
  ]
}
```

| category | 설명 |
| --- | --- |
| QUESTION | 질문 중심 |
| OPINION | 의견 중심 |
| REQUEST | 요청·제안 중심 |
| CONFUSION | 이해 부족 표현 중심 |
| MIXED | 여러 유형이 섞인 주제 |

규칙:

- AI 입력은 청중이 작성한 메시지로 한정하고 강연자 메시지는 제외한다.
- 물음표 없는 의견도 분석 대상이다. 같은 주제의 서로 다른 유형을 그룹화할 수 있다.
- 서로 반대되는 의견을 하나의 합의된 의견으로 왜곡하지 않고, 원문에 없는 의견을 생성하지 않는다.
- `sourceMessageIds` 는 실제 DB 메시지와 대조하며, 다른 강연방·분석 구간 밖 메시지 참조를 차단한다.
- 대표 질문 전용 컬럼은 없다. 메시지가 없는 구간의 불필요한 호출은 생략을 권장한다.
- API 실패 시 FAILED 처리하며 AI 실패가 채팅·이해도·투표에 영향을 주지 않는다.
- 중복 분석 제약: `UNIQUE(lecture_room_id, window_start_at, window_end_at)` — 같은 구간을 중복 분석하지 않도록 작업 상태를 관리한다.

엔터티: `ai_analyses`, `ai_topic_groups`, `ai_topic_group_messages`

### [AI-CHAT-06] AI 분석 결과 조회

**GET** `/api/v1/rooms/{roomId}/ai/analyses?limit=10` — HOST, 강연방 소유자 (limit 기본 10, 최대 50 제안)

```json
{
  "roomId": 10,
  "analyses": [
    {
      "analysisId": 301,
      "windowStartAt": "2026-11-17T01:01:30Z",
      "windowEndAt": "2026-11-17T01:02:00Z",
      "status": "COMPLETED",
      "completedAt": "2026-11-17T01:02:08Z",
      "topicGroups": [
        {
          "topicGroupId": 401,
          "topic": "Redis 사용 이유",
          "category": "MIXED",
          "summary": "Redis 도입 이유와 활용 사례에 대한 질문과 의견이 있었습니다.",
          "messageCount": 2,
          "sourceMessageIds": [1001, 1003]
        }
      ]
    }
  ],
  "lastCompletedAt": "2026-11-17T01:02:08Z"
}
```

규칙: 최신·이전 분석 결과 조회, 강연방 소유권 검증, `messageCount` 는 실제 연결된 원문 수로 계산, 분석 실패 시 상태 표시, 내부 오류 상세는 반환하지 않음.

### [AI-CHAT-07, DASH-05] AI 의견 요약 관련 원문 조회

**GET** `/api/v1/rooms/{roomId}/ai/topic-groups/{topicGroupId}/messages` — HOST, 강연방 소유자

```json
{
  "topicGroupId": 401,
  "topic": "Redis 사용 이유",
  "messages": [
    { "messageId": 1001, "participantId": 101, "nickname": "청중A", "content": "Redis를 꼭 사용해야 하나요?", "receivedAt": "2026-11-17T01:01:40Z" },
    { "messageId": 1003, "participantId": 102, "nickname": "청중B", "content": "MySQL만으로 처리하는 예시도 보여 주세요.", "receivedAt": "2026-11-17T01:01:50Z" }
  ]
}
```

규칙: 주제별 요약의 근거 원문 조회, 같은 강연방의 메시지만 반환, AI 요약 문장을 원문으로 표시하지 않음, 연결 테이블로 메시지를 조회한다.
엔터티: `ai_topic_groups`, `ai_topic_group_messages`, `chat_messages`

---

## 9. DASH — 실시간 대시보드

별도 대시보드 엔터티는 없다. 기존 API 의 조회 결과와 WebSocket 이벤트를 조합해 화면을 구성한다.

### 9-1. 기능 매핑

| 기능 ID | 기능 | 데이터 조회 API | 실시간 이벤트 |
| --- | --- | --- | --- |
| DASH-01 | 실시간 채팅 | GET `/rooms/{roomId}/messages` | CHAT_CREATED |
| DASH-02 | 현재 이해도 분포 | GET `/rooms/{roomId}/understanding/summary` | UNDERSTANDING_SUMMARY_UPDATED |
| DASH-03 | 투표 현황 | GET `/rooms/{roomId}/polls` 및 결과 조회 | POLL_CREATED / POLL_RESULTS_UPDATED |
| DASH-04 | AI 주제별 의견 요약 | GET `/rooms/{roomId}/ai/analyses` | AI_ANALYSIS_COMPLETED |
| DASH-05 | 관련 채팅 원문 | GET `/rooms/{roomId}/ai/topic-groups/{topicGroupId}/messages` | 별도 이벤트 불필요 |

표의 REST 경로는 공통 Base URL `/api/v1` 을 생략했다.

### 9-2. 이해도 집계 갱신 이벤트 — SUBSCRIBE `/topic/rooms/{roomId}/understanding`

```json
{
  "type": "UNDERSTANDING_SUMMARY_UPDATED",
  "data": {
    "roomId": 10,
    "totalParticipants": 20,
    "respondedParticipants": 15,
    "unrespondedParticipants": 5,
    "counts": { "UNDERSTOOD": 8, "CONFUSED": 5, "NOT_UNDERSTOOD": 2 },
    "percentagesOfAllParticipants": { "UNDERSTOOD": 40.0, "CONFUSED": 25.0, "NOT_UNDERSTOOD": 10.0 }
  }
}
```

### 9-3. 투표 생성 이벤트 — SUBSCRIBE `/topic/rooms/{roomId}/polls`

```json
{
  "type": "POLL_CREATED",
  "data": {
    "pollId": 501,
    "roomId": 10,
    "question": "Redis가 필요한 이유를 이해했나요?",
    "status": "OPEN",
    "options": [
      { "optionId": 1, "content": "이해했어요" },
      { "optionId": 2, "content": "조금 헷갈려요" }
    ]
  }
}
```

### 9-4. 투표 결과 갱신 이벤트 — SUBSCRIBE `/topic/rooms/{roomId}/polls`

```json
{
  "type": "POLL_RESULTS_UPDATED",
  "data": { "pollId": 501, "respondedParticipants": 12, "counts": { "1": 6, "2": 4, "3": 2 } }
}
```

### 9-5. AI 분석 완료 이벤트 — SUBSCRIBE `/topic/rooms/{roomId}/ai`

```json
{
  "type": "AI_ANALYSIS_COMPLETED",
  "data": {
    "analysisId": 301,
    "roomId": 10,
    "completedAt": "2026-11-17T01:02:08Z",
    "topicGroups": [
      { "topicGroupId": 401, "topic": "Redis 사용 이유", "category": "MIXED", "summary": "Redis 사용 이유와 실제 사례에 대한 의견이 있었습니다.", "messageCount": 2 }
    ]
  }
}
```

### 9-6. 강연 상태 변경 이벤트 — SUBSCRIBE `/topic/rooms/{roomId}/status`

```json
{ "type": "ROOM_STATUS_CHANGED", "data": { "roomId": 10, "status": "ENDED", "changedAt": "2026-11-17T02:00:00Z" } }
```

### WebSocket 보안 규칙

- 강연방별 채널을 분리한다.
- 청중은 본인이 참여한 강연의 허용된 채널만 구독할 수 있다.
- 강연자 전용 `ai`, `understanding` 채널은 강연방 소유자에게만 허용한다.
- 구독 시에도 서버에서 권한을 검증하고, 청중에게 비공개 집계 데이터가 노출되지 않게 한다.
- 순수 WebSocket 을 사용하면 경로 대신 메시지 `type` 으로 구분하는 방식으로 바꾼다.

---

## 10. REPORT — AI 회고 보고서

### [REPORT-01] AI 회고 보고서 생성 요청

**POST** `/api/v1/rooms/{roomId}/reports` — HOST, 강연방 소유자 — Request `{}`

Response — 202 Accepted

```json
{
  "reportId": 601,
  "roomId": 10,
  "status": "PENDING",
  "createdAt": "2026-11-17T02:00:10Z",
  "message": "회고 보고서 생성 요청이 접수되었습니다."
}
```

실행 조건: 강연 ENDED, 마지막 STT 발언 저장 완료, 투표 응답 집계 확정, 잔여 채팅 분석 작업 완료 또는 실패 구간 확정, 요청자가 강연방 소유자.
규칙: 종료된 강연만 생성, 강연방당 보고서 하나를 기본으로 관리, 중복 생성 요청 시 기존 보고서 ID·상태 반환(LLM 중복 호출 방지), AI 보고서 생성은 비동기, 데이터 준비가 끝나지 않았으면 생성 거부, 실패 시 FAILED 기록.
오류: `403 FORBIDDEN`, `404 ROOM_NOT_FOUND`, `409 ROOM_NOT_ENDED`, `409 REPORT_INPUT_NOT_READY` · 엔터티: `lecture_reports`

### [REPORT-02, 03, 04, 05, 07] 보고서 내부 데이터 처리

별도 외부 Request API 는 없다.

수집 대상: `stt_segments`(실제 발언), `stt_gaps`(누락 구간), `chat_messages`(청중 채팅 원문), `ai_topic_groups`(주제별 의견 요약), `understanding_histories`(이해도 변경 이력), `participant_understandings`(현재 이해 상태), `polls`·`poll_responses`(투표 결과).

정상 흐름: 강연 데이터 조회 → 시간순 정렬 → 시간대별 분석 구간 생성 → STT 발언 연결 → 이해도 변경 이력 집계 → 청중 채팅·AI 의견 요약 연결 → 투표 결과 연결 → LLM 회고 요약 생성 → AI 결과·근거 검증 → 구조화된 보고서 JSON 저장.

시간대별 연결 기준

| 데이터 | 연결 기준 |
| --- | --- |
| STT 발언 | 발언 추정 시작·종료 시각 |
| 채팅 | 서버 수신 시각 |
| 이해도 상태 변경 | 변경 시각 |
| 투표 | 생성·종료 및 응답 시각 |
| AI 의견 요약 | 분석 구간 및 근거 메시지 시각 |

기본 구간: 강연 시작 시점 기준 60초 단위 분할을 제안한다(예: 10:00:00~10:01:00). 구간은 `[start, end)` 규칙을 적용해 경계 시각이 중복 집계되지 않게 한다.

보고서 JSON 예시

```json
{
  "overview": "Redis와 MySQL의 역할 차이에 대한 의견이 반복되었습니다.",
  "timeline": [
    {
      "startAt": "2026-11-17T01:12:00Z",
      "endAt": "2026-11-17T01:13:00Z",
      "sttSegments": [
        { "segmentId": 9001, "transcript": "Redis는 메모리 기반 데이터 저장소입니다." }
      ],
      "sttGaps": [],
      "understanding": {
        "confusedTransitions": 6,
        "latestCountsAtEnd": { "UNDERSTOOD": 8, "CONFUSED": 6, "NOT_UNDERSTOOD": 2 }
      },
      "audienceOpinions": [
        {
          "topic": "Redis 사용 이유",
          "category": "MIXED",
          "summary": "Redis 도입 이유를 궁금해하는 의견이 있었습니다.",
          "topicGroupIds": [401],
          "sourceMessageIds": [1001, 1003]
        }
      ],
      "polls": [
        { "pollId": 501, "question": "Redis가 필요한 이유를 이해했나요?", "respondedParticipants": 12 }
      ],
      "aiInterpretation": "이 구간에서 이해 부족 반응이 관찰되어 보충 설명을 검토할 수 있습니다."
    }
  ],
  "dataLimitations": ["STT 전사 시각은 브라우저 추정값입니다."],
  "evidencePolicy": "동일 시간대의 관찰은 인과관계를 의미하지 않습니다."
}
```

핵심 규칙:

- 보고서는 실제 수집된 데이터로 생성하며, 강연 발언 원문을 LLM 이 임의로 생성하지 않는다.
- STT 누락 구간은 별도 표시한다('발언 기록 없음').
- 이해도 변경 건수와 현재 이해 상태 인원을 구분한다.
- AI 해석과 실제 기록을 다른 필드로 구분한다.
- 청중 의견에 서로 다른 입장이 있으면 모두 보존하고, 질문이 없어도 의견·요청·반응을 분석에 포함한다.
- 관련 원문 ID 는 실제 DB 데이터와 일치해야 한다.
- 같은 시간대라는 이유로 발언과 반응 사이의 인과관계를 단정하지 않는다.

### [REPORT-06] AI 회고 보고서 조회

**GET** `/api/v1/rooms/{roomId}/reports` — HOST, 강연방 소유자

완료(200 OK)

```json
{
  "reportId": 601,
  "roomId": 10,
  "status": "COMPLETED",
  "createdAt": "2026-11-17T02:00:10Z",
  "generatedAt": "2026-11-17T02:00:30Z",
  "content": {
    "overview": "Redis와 MySQL의 역할 차이에 대한 의견이 반복되었습니다.",
    "timeline": [ { "startAt": "2026-11-17T01:12:00Z", "endAt": "2026-11-17T01:13:00Z", "sttSegments": [], "sttGaps": [], "understanding": { "confusedTransitions": 6 }, "audienceOpinions": [], "polls": [], "aiInterpretation": "해당 구간의 보충 설명을 검토할 수 있습니다." } ],
    "dataLimitations": [],
    "evidencePolicy": "시간적 동시성은 인과관계를 의미하지 않습니다."
  }
}
```

생성 중(200 OK): `{ "reportId": 601, "status": "PROCESSING", "content": null, "generatedAt": null }`
생성 실패(200 OK): `{ "reportId": 601, "status": "FAILED", "content": null, "errorCode": "LLM_REQUEST_FAILED" }`

규칙: 보고서가 없으면 404, 생성 중이면 PROCESSING, 실패면 FAILED, 완료는 저장된 JSON 반환, **조회 시 LLM API 를 재호출하지 않음**, 다른 강연방 보고서 조회 차단.

---

## 11. P0 기능 정의서 ↔ API 대응표

`/api/v1` 은 생략했다.

| 기능 ID | API 또는 구현 방식 |
| --- | --- |
| AUTH-01~04 | POST `/auth/signup`, POST `/auth/login`, GET `/auth/me`, POST `/auth/logout` |
| ROOM-01 | POST `/rooms` |
| ROOM-02 | GET `/rooms/{roomId}/invitation` |
| ROOM-03 | GET `/rooms/{roomId}`, GET `/public/rooms/{joinCode}` |
| ROOM-04 | POST `/rooms/{roomId}/start`, POST `/rooms/{roomId}/end` |
| ROOM-05 | POST `/public/rooms/{joinCode}/participants` |
| ROOM-06, 07 | REST 및 WebSocket 권한·종료 상태 검증 |
| CHAT-01, 02, 04, 05 | WebSocket 채팅 송수신·저장·검증·격리 |
| CHAT-03 | GET `/rooms/{roomId}/messages` |
| FEED-01, 02, 03, 05 | PUT/GET `/rooms/{roomId}/understanding/me`, 이해도 변경 트랜잭션에서 이력 저장 |
| FEED-04, 06 | GET `/rooms/{roomId}/understanding/summary` |
| POLL-01 | POST 투표 생성 / POST 투표 종료 / GET 투표 목록 |
| POLL-02 | POST 투표 응답 |
| POLL-03 | GET 투표 결과 / 응답 중복 방지 |
| STT-01 | POST 세션 시작 / POST 세션 종료 |
| STT-02, 03 | POST 확정 전사 저장 |
| STT-04 | POST 누락 시작 / PATCH 누락 종료 |
| STT-05 | GET STT 발언 및 누락 구간 |
| AI-CHAT-01~05, 08 | 내부 스케줄러·LLM 분석·저장·실패 처리 |
| AI-CHAT-06 | GET `/rooms/{roomId}/ai/analyses` |
| AI-CHAT-07, DASH-05 | GET 주제 그룹 관련 원문 조회 |
| DASH-01~04 | 기존 조회 API + WebSocket 이벤트 |
| REPORT-01 | POST `/rooms/{roomId}/reports` |
| REPORT-02~05, 07 | 내부 데이터 수집·시간대 연결·LLM 초안 |
| REPORT-06 | GET `/rooms/{roomId}/reports` |

---

## 12. 주요 통합 테스트 시나리오

| 테스트 | 검증 내용 |
| --- | --- |
| 전체 E2E | 회원가입 → 로그인 → 강연방 생성 → 참여 → STT → 채팅·이해도·투표 → AI 분석 → 강연 종료 → 회고 보고서 |
| 강연방 소유권 | 다른 강연자의 강연방에 대한 관리 요청 차단 |
| 청중 권한 | 다른 강연방의 채팅·이해도·투표 접근 차단 |
| 강연 상태 | READY 에서 채팅 차단, ENDED 에서 신규 응답 차단 |
| 채팅 저장 | 강연자·청중 메시지 정상 저장 및 발신자 구분 |
| AI 분석 대상 | 강연자 메시지는 청중 의견 요약에서 제외 |
| 이해도 정합성 | 같은 상태 재요청 시 이력·집계 중복 없음 |
| 이해도 동시성 | 빠른 상태 변경 후 최신 상태와 이력 일치 |
| 투표 정합성 | 동일 참가자의 중복 응답 거부 |
| 투표 소속 검증 | 다른 투표의 선택지로 응답하는 요청 거부 |
| STT 저장 | 확정 발언 및 시간 기록 정상 저장 |
| STT 중복 전송 | 같은 segmentKey 재전송 시 중복 생성 없음 |
| STT 오류 | 인식 중단 시 누락 구간 생성·종료 |
| AI 원문 검증 | 없는 메시지·다른 강연 메시지·분석 구간 밖 메시지 참조 거절 |
| AI 장애 | LLM 실패해도 채팅·이해도·투표 정상 |
| 종료 직전 데이터 | 마지막 STT 발언이 회고 보고서에 포함 |
| 회고 사실 검증 | 실제 통계·STT 원문·AI 해석 구분 |
| 보고서 조회 | 완료된 보고서 조회 시 LLM 재호출 없음 |

---

## 13. ERD ↔ API 정합성

| 항목 | 적용 내용 |
| --- | --- |
| 강연자 채팅 | `chat_messages.host_user_id` 사용 |
| 청중 채팅 | `chat_messages.participant_id` 사용 |
| AI 청중 의견 | 강연자 채팅 제외 |
| 주제별 AI 요약 | `ai_topic_groups.topic`, `summary`, `category` |
| 원문 메시지 연결 | `ai_topic_group_messages` |
| AI 중복 분석 | 강연방·분석 시작·종료 구간 UNIQUE |
| STT 중복 저장 | 세션·segmentKey UNIQUE |
| STT 누락 이벤트 | `event_key` 및 UNIQUE |
| 이해도 최신 상태 | `participant_understandings` |
| 이해도 변경 이력 | `understanding_histories` |
| 투표 중복 방지 | `(poll_id, participant_id)` UNIQUE |
| 시간대별 회고 | 원천 데이터 집계 후 보고서 JSON 저장 |
| 보고서 중복 생성 | `lecture_reports.lecture_room_id` UNIQUE |
| QR 이미지 | 별도 DB 저장 없음 |

---

## 14. 개발 전 팀 합의 필요 사항

| 항목 | 현재 제안 |
| --- | --- |
| 강연자 인증 | 세션 쿠키 |
| 청중 인증 | 비회원 참여 쿠키 |
| 실시간 통신 | WebSocket + STOMP |
| 강연방 상태 | READY → LIVE → ENDED |
| 강연 시작 전 입장 | READY 에서도 청중 입장 허용 |
| 강연자 채팅 | 허용 |
| AI 의견 요약 대상 | 청중 채팅만 |
| 이해도 비율 분모 | 누적 고유 청중 수 |
| 투표 | 단일 선택, 재응답 불가 |
| 동시 투표 | 강연방당 OPEN 하나 |
| STT | 브라우저 Web Speech API |
| STT 저장 | 최종 확정 결과만 저장 |
| STT 중단 | 누락 구간 기록 |
| 실시간 AI 분석 주기 | 30초 |
| 보고서 시간 구간 | 60초 |
| AI 분석 중복 | 동일 구간 중복 실행 방지 |
| 보고서 생성 | 강연 종료 후 비동기 요청 |
| 보고서 저장 | 강연당 보고서 하나 |
| API 비용 제한 | P0 기본 호출량·입력량 제한 |
| STT·채팅 보존 기간 | 추후 확정 |

---

## 15. MVP 완료 기준

기능별 완료 기준은 `docs/feature-spec.md` §14 와 `docs/planning.md` §11 을 따른다. 요약:

- 강연자 인증, 강연방 생성·QR 입장·권한 구분·시작/종료·종료 후 응답 차단
- 강연자·청중 실시간 채팅, 저장·조회·검증·강연방 격리
- 이해도 선택·변경·이력·집계, 투표 생성·종료·응답·중복 방지
- STT 시작·중지·저장·누락 구간·조회
- 주기적 AI 분석·주제별 요약·원문 연결·실패 처리
- 대시보드(채팅·이해도·투표·AI 요약·근거 원문)
- 강연 종료 후 AI 회고 보고서 생성·저장·조회, 실제 근거와 AI 생성 내용 구분, STT 누락의 임의 보완 금지
- 공개 URL, HTTPS/WSS, 환경변수 보안, 단위·통합 테스트, 강연방 간 격리 검증, 이해도·투표 정합성 검증, 10~20명 모의 강연, E2E
