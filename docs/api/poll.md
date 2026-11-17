# POLL — 실시간 투표


### [POLL-01] 투표 생성

**POST** `/api/v1/rooms/{roomId}/polls` — HOST, 강연방 소유자

Request

```json
{ "question": "Redis가 필요한 이유를 이해했나요?", "options": ["이해했어요", "조금 헷갈려요", "다시 설명이 필요해요"] }
```

Response — 201 Created

```json
{
  "data": {
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
}
```

규칙: 강연자만, LIVE 에서만 생성, P0 은 단일 선택 투표, 선택지 2~5개, 강연방당 진행 중(OPEN) 투표 최대 1개를 제안한다, 생성 시 청중에게 WebSocket 이벤트 전달. 엔터티: `polls`, `poll_options`

### [POLL-01] 투표 종료

**POST** `/api/v1/rooms/{roomId}/polls/{pollId}/close` — HOST, 강연방 소유자

```json
{ "data": { "pollId": 501, "status": "CLOSED", "closedAt": "2026-11-17T01:23:00Z" } }
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
{ "data": { "pollId": 501, "participantId": 101, "optionId": 2, "respondedAt": "2026-11-17T01:21:00Z" } }
```

규칙: 강연 LIVE 및 투표 OPEN 일 때만, 해당 강연방 참여자만, 한 청중은 투표당 한 번만 응답, 응답 후 선택지 변경 불가(P0 제안), 선택지는 해당 Poll 에 속해야 함, `(poll_id, participant_id)` UNIQUE, 응답 저장 후 실시간 결과 갱신.
오류: `400 INVALID_POLL_OPTION`, `403 FORBIDDEN`, `404 POLL_NOT_FOUND`, `409 ALREADY_VOTED`, `409 POLL_CLOSED`, `409 ROOM_ENDED` · 엔터티: `poll_responses`

### [POLL-01] 강연방 투표 목록 조회

**GET** `/api/v1/rooms/{roomId}/polls?status=OPEN|CLOSED` — HOST 또는 해당 PARTICIPANT

```json
{
  "data": {
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
}
```

규칙: 강연별 투표 목록 반환, 청중은 질문·선택지 조회 가능, 다른 강연의 투표 조회 금지, 종료된 투표도 기록으로 조회 가능.

### [POLL-03] 투표 결과 조회

**GET** `/api/v1/rooms/{roomId}/polls/{pollId}/results` — HOST, 강연방 소유자

```json
{
  "data": {
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
}
```

규칙: 중복 응답 제외, 실제 `poll_responses` 기준 집계, 선택지별 비율의 분모는 해당 투표 응답자 수(이해도 비율의 분모와 구분), 강연방 소유권 검증.

