# DASH — 실시간 대시보드


별도 대시보드 엔터티는 없다. 기존 API 의 조회 결과와 WebSocket 이벤트를 조합해 화면을 구성한다.

### 9-1. 기능 매핑

| 기능 ID | 기능 | 데이터 조회 API | 실시간 이벤트 |
| --- | --- | --- | --- |
| DASH-01 | 실시간 채팅 | GET `/rooms/{roomId}/messages` | CHAT_CREATED |
| DASH-02 | 이해도 체크 현황 | GET `/rooms/{roomId}/polls?status=OPEN` 및 `/polls/{pollId}/results`(`type=UNDERSTANDING`) | POLL_CREATED / POLL_RESULTS_UPDATED / POLL_CLOSED |
| DASH-03 | 투표 현황 | GET `/rooms/{roomId}/polls` 및 결과 조회 | POLL_CREATED / POLL_RESULTS_UPDATED / POLL_CLOSED |
| DASH-04 | AI 주제별 의견 요약 | GET `/rooms/{roomId}/ai/analyses` | AI_ANALYSIS_COMPLETED |
| DASH-05 | 관련 채팅 원문 | GET `/rooms/{roomId}/ai/topic-groups/{topicGroupId}/messages` | 별도 이벤트 불필요 |

표의 REST 경로는 공통 Base URL `/api/v1` 을 생략했다.

### 9-2. 투표 생성 이벤트 — SUBSCRIBE `/topic/rooms/{roomId}/polls`

청중과 강연자가 모두 구독한다. 이해도 체크(`type=UNDERSTANDING`)도 같은 이벤트로 전달한다.

```json
{
  "type": "POLL_CREATED",
  "data": {
    "pollId": 501,
    "roomId": 10,
    "type": "UNDERSTANDING",
    "question": "지금 설명을 이해하셨나요?",
    "status": "OPEN",
    "options": [
      { "optionId": 1, "content": "이해됨", "understandingStatus": "UNDERSTOOD" },
      { "optionId": 2, "content": "헷갈림", "understandingStatus": "CONFUSED" },
      { "optionId": 3, "content": "모르겠음", "understandingStatus": "NOT_UNDERSTOOD" }
    ],
    "closesAt": "2026-11-17T01:21:00Z",
    "serverTime": "2026-11-17T01:20:00Z"
  }
}
```

### 9-3. 투표 마감 이벤트 — SUBSCRIBE `/topic/rooms/{roomId}/polls`

청중과 강연자가 모두 구독한다. 강연자가 직접 마감하거나, `closesAt` 이 지나 서버 스케줄러가 자동 마감하거나, 강연이 종료될 때 전파한다. 결과 수치는 담지 않는다.

```json
{
  "type": "POLL_CLOSED",
  "data": { "pollId": 501, "roomId": 10, "closedAt": "2026-11-17T01:21:00Z", "closeReason": "TIMEOUT" }
}
```

### 9-4. 투표 결과 갱신 이벤트 — SUBSCRIBE `/topic/rooms/{roomId}/polls/results`

강연방 소유자에게만 허용한다. 응답이 들어오거나 바뀔 때 전파하며 청중에게는 보내지 않는다.

```json
{
  "type": "POLL_RESULTS_UPDATED",
  "data": {
    "pollId": 501,
    "totalParticipants": 20,
    "respondedParticipants": 12,
    "unrespondedParticipants": 8,
    "counts": { "1": 6, "2": 4, "3": 2 }
  }
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
- 강연자 전용 `ai`, `polls/results` 채널은 강연방 소유자에게만 허용한다. 투표 결과는 청중에게 공개하지 않는다.
- 구독 시에도 서버에서 권한을 검증하고, 청중에게 비공개 집계 데이터가 노출되지 않게 한다.
- 순수 WebSocket 을 사용하면 경로 대신 메시지 `type` 으로 구분하는 방식으로 바꾼다.

