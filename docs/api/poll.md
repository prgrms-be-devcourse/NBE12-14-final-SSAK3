# POLL — 실시간 투표와 이해도 체크

투표 종류(`type`)는 `GENERAL`(강연자가 질문·선택지 작성)과 `UNDERSTANDING`(이해도 체크, 선택지 고정)이다. 이해도 체크는 별도 기능이 아니라 투표의 한 종류이므로 같은 API 를 쓴다.
모든 투표에는 제한 시간이 있고, 청중은 마감 전까지 응답을 바꿀 수 있으며, 마감되면 마지막 선택이 최종 응답이 된다. 결과는 강연자만 볼 수 있다.

### [POLL-01] 투표 생성

**POST** `/api/v1/rooms/{roomId}/polls` — HOST, 강연방 소유자

Request — 일반 투표

```json
{ "type": "GENERAL", "question": "Redis가 필요한 이유를 이해했나요?", "options": ["이해했어요", "조금 헷갈려요", "다시 설명이 필요해요"], "durationSeconds": 90 }
```

Request — 이해도 체크

```json
{ "type": "UNDERSTANDING", "durationSeconds": 60 }
```

`type` 을 생략하면 `GENERAL` 이다. `durationSeconds` 를 생략하면 60초이고 10~300 만 허용한다. `UNDERSTANDING` 에서 `question` 을 생략하면 서버가 "지금 설명을 이해하셨나요?" 로 채우고, `options` 는 보내도 무시한다.

Response — 201 Created

```json
{
  "data": {
    "pollId": 501,
    "roomId": 10,
    "type": "UNDERSTANDING",
    "question": "지금 설명을 이해하셨나요?",
    "status": "OPEN",
    "options": [
      { "optionId": 1, "content": "이해됨", "displayOrder": 1, "understandingStatus": "UNDERSTOOD" },
      { "optionId": 2, "content": "헷갈림", "displayOrder": 2, "understandingStatus": "CONFUSED" },
      { "optionId": 3, "content": "모르겠음", "displayOrder": 3, "understandingStatus": "NOT_UNDERSTOOD" }
    ],
    "durationSeconds": 60,
    "createdAt": "2026-11-17T01:20:00Z",
    "closesAt": "2026-11-17T01:21:00Z",
    "serverTime": "2026-11-17T01:20:00Z"
  }
}
```

`understandingStatus` 는 `UNDERSTANDING` 투표의 선택지에만 있고 `GENERAL` 에서는 `null` 이다. 청중 화면의 남은 시간은 `closesAt − serverTime` 으로 계산하며 표시용일 뿐, 마감 여부는 서버가 판단한다.

규칙: 강연자만, LIVE 에서만 생성, `GENERAL` 은 단일 선택에 선택지 2~5개, `UNDERSTANDING` 은 선택지 3개를 서버가 고정, 서버가 `closesAt = 생성 시각 + durationSeconds` 로 계산해 저장, 강연방당 진행 중(OPEN) 투표는 종류와 관계없이 1개만 허용, 생성 시 청중에게 WebSocket 이벤트 전달.
오류: `400 INVALID_INPUT`, `403 FORBIDDEN`, `404 ROOM_NOT_FOUND`, `409 ROOM_NOT_LIVE`, `409 ROOM_ENDED`, `409 POLL_ALREADY_OPEN` · 엔터티: `polls`, `poll_options`

### [POLL-01] 투표 마감

**POST** `/api/v1/rooms/{roomId}/polls/{pollId}/close` — HOST, 강연방 소유자

```json
{ "data": { "pollId": 501, "status": "CLOSED", "closedAt": "2026-11-17T01:20:40Z", "closeReason": "HOST" } }
```

`closeReason` 은 마감 원인이며 `HOST`(강연자가 직접), `TIMEOUT`(`closesAt` 도달), `ROOM_ENDED`(강연 종료) 중 하나다.

규칙: OPEN 에서만 마감, 마감 후 응답 차단, `closesAt` 에 도달하면 서버가 자동으로 마감하고 `closedAt` 은 `closesAt`, 강연 종료 시 진행 중인 투표도 자동 마감, 마감 이벤트 WebSocket 전파. 마감 후에도 응답한 청중의 마지막 선택은 그대로 최종 응답이다(별도 제출 없음).
오류: `403 FORBIDDEN`, `404 POLL_NOT_FOUND`, `409 POLL_ALREADY_CLOSED`

### [POLL-02, 03] 청중 투표 응답

**PUT** `/api/v1/rooms/{roomId}/polls/{pollId}/responses/me` — PARTICIPANT

```json
{ "optionId": 2 }
```

Response — 200 OK

```json
{ "data": { "pollId": 501, "participantId": 101, "optionId": 2, "changed": true, "respondedAt": "2026-11-17T01:20:30Z", "closesAt": "2026-11-17T01:21:00Z" } }
```

처음 보내면 저장하고, 마감 전에 다시 보내면 선택을 바꾼다. 같은 선택을 반복해서 보내면 `changed` 가 `false` 이고 저장된 값은 달라지지 않는다. `respondedAt` 은 마지막으로 선택이 바뀐 서버 시각이다.

규칙: 강연 LIVE 이고 투표가 OPEN 이며 서버 시각이 `closesAt` 이전일 때만, 해당 강연방 참여자만(participantId 는 서버가 확인), 선택지는 해당 투표에 속해야 함, 한 청중의 응답은 투표당 1건(`(poll_id, participant_id)` UNIQUE)이며 변경 이력은 남기지 않음, 동시 요청이 겹쳐도 마지막으로 처리된 선택이 최종 응답이고 집계와 일치, 마감 판단과 저장은 같은 트랜잭션에서 처리.
오류: `400 INVALID_POLL_OPTION`, `403 FORBIDDEN`, `404 POLL_NOT_FOUND`, `409 POLL_CLOSED`(마감 시각이 지난 요청 포함), `409 ROOM_ENDED` · 엔터티: `poll_responses`

### [POLL-01] 강연방 투표 목록 조회

**GET** `/api/v1/rooms/{roomId}/polls?status=OPEN|CLOSED` — HOST 또는 해당 PARTICIPANT

```json
{
  "data": {
    "polls": [
      {
        "pollId": 501,
        "type": "GENERAL",
        "question": "Redis가 필요한 이유를 이해했나요?",
        "status": "OPEN",
        "options": [
          { "optionId": 1, "content": "이해했어요", "understandingStatus": null },
          { "optionId": 2, "content": "조금 헷갈려요", "understandingStatus": null }
        ],
        "createdAt": "2026-11-17T01:20:00Z",
        "closesAt": "2026-11-17T01:21:30Z",
        "closedAt": null,
        "closeReason": null
      }
    ],
    "serverTime": "2026-11-17T01:20:10Z"
  }
}
```

규칙: 강연별 투표 목록 반환, 청중은 질문·선택지·남은 시간만 조회(응답 인원이나 선택 결과는 내려주지 않음), 다른 강연의 투표 조회 금지, 마감된 투표도 기록으로 조회 가능, 서버 시각 기준으로 `closesAt` 이 지난 투표는 `CLOSED` 로 표시한다.

### [POLL-03, 05] 투표 결과 조회

**GET** `/api/v1/rooms/{roomId}/polls/{pollId}/results` — HOST, 강연방 소유자

```json
{
  "data": {
    "pollId": 501,
    "type": "UNDERSTANDING",
    "status": "OPEN",
    "closesAt": "2026-11-17T01:21:00Z",
    "totalParticipants": 20,
    "respondedParticipants": 12,
    "unrespondedParticipants": 8,
    "responseRate": 60.0,
    "options": [
      { "optionId": 1, "content": "이해됨", "understandingStatus": "UNDERSTOOD", "count": 6, "percentageOfResponses": 50.0, "percentageOfAllParticipants": 30.0 },
      { "optionId": 2, "content": "헷갈림", "understandingStatus": "CONFUSED", "count": 4, "percentageOfResponses": 33.3, "percentageOfAllParticipants": 20.0 },
      { "optionId": 3, "content": "모르겠음", "understandingStatus": "NOT_UNDERSTOOD", "count": 2, "percentageOfResponses": 16.7, "percentageOfAllParticipants": 10.0 }
    ],
    "calculatedAt": "2026-11-17T01:20:40Z"
  }
}
```

규칙: 투표를 연 강연방의 소유자만 진행 중에도 실시간으로 조회하고 청중에게는 공개하지 않음, 응답은 `poll_responses` 의 청중별 최종 선택 기준이라 중복 집계 없음, `totalParticipants` 는 누적 고유 참여자(현재 접속 인원과 구분), 미응답 = 전체 − 응답이며 모르겠음으로 간주하지 않음, `percentageOfResponses` 의 분모는 응답자 수이고 `percentageOfAllParticipants` 의 분모는 전체 참여자 수이므로 서로 구분해 표시, 소수 첫째 자리 반올림(분모가 0 이면 0.0), 청중 개개인의 선택은 내려주지 않음, 마감·강연 종료 후에는 결과가 변하지 않음, 강연방 소유권 검증.
오류: `403 FORBIDDEN`, `404 POLL_NOT_FOUND`

### [POLL-07] 내 응답 조회 (P1)

**GET** `/api/v1/rooms/{roomId}/polls/{pollId}/responses/me` — PARTICIPANT

재접속한 청중이 진행 중인 투표와 자신의 선택을 복원하는 데 쓴다. 응답하지 않았으면 `{ "data": { "pollId": 501, "participantId": 101, "optionId": null, "respondedAt": null } }` 이며, 미응답은 어떤 선택지로도 간주하지 않는다.

### [POLL-06] 이해도 체크 라운드 비교 (P1)

**GET** `/api/v1/rooms/{roomId}/polls/understanding-rounds` — HOST, 강연방 소유자

```json
{
  "data": {
    "roomId": 10,
    "ratio": 40.0,
    "minCount": 3,
    "rounds": [
      {
        "pollId": 501,
        "openedAt": "2026-11-17T01:20:00Z",
        "closedAt": "2026-11-17T01:21:00Z",
        "totalParticipants": 20,
        "respondedParticipants": 12,
        "counts": { "UNDERSTOOD": 6, "CONFUSED": 4, "NOT_UNDERSTOOD": 2 },
        "lackCount": 6,
        "lackRatio": 50.0,
        "lowUnderstanding": true
      }
    ]
  }
}
```

규칙: 마감된 `UNDERSTANDING` 투표를 시간순으로 나열해 시간대별 이해도 변화를 보여 준다. `lackCount` 는 `CONFUSED + NOT_UNDERSTOOD`, `lackRatio` 의 분모는 응답자 수이며, `lackCount ≥ minCount`(기본 3) 이고 `lackRatio ≥ ratio`(기본 40, 0 초과 100 이하)이면 `lowUnderstanding` 이 `true` 다. 결과는 관찰한 수치이며 원인을 단정하지 않는다. 진행 중인 라운드는 포함하지 않는다.
